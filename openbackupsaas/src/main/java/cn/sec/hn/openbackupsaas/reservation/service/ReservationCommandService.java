package cn.sec.hn.openbackupsaas.reservation.service;

import cn.sec.hn.openbackupsaas.command.service.IdempotencyService;
import cn.sec.hn.openbackupsaas.journal.domain.AuditActions;
import cn.sec.hn.openbackupsaas.journal.service.JournalService;
import cn.sec.hn.openbackupsaas.reservation.domain.LineStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.OccupancyKind;
import cn.sec.hn.openbackupsaas.reservation.domain.OccupancyStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.Reservation;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationConflictException;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationLine;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationTransitions;
import cn.sec.hn.openbackupsaas.reservation.domain.RoomOccupancy;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationMapper;
import cn.sec.hn.openbackupsaas.reservation.web.ReservationView;
import cn.sec.hn.openbackupsaas.room.domain.PhysicalRoom;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单命令（S1-08 建单 + S1-09 状态机）：把订单的写操作收进<b>短事务</b>并对客户端幂等。
 *
 * <p><b>建单原子性（S1-08）：</b>多房多晚要么全成、要么全败。任一房型在所选区间挑不到可用房间，
 * 整单在业务层抛出结构化冲突（409），事务回滚，不留下"部分房间被占"的半截状态。
 *
 * <p><b>状态机命令（S1-09）：</b>确认 / 取消 / No-show 走<b>整单统一迁移</b>（DEC-04 部分履约待定，
 * 见 S0-02 §5.1）。每个命令：锁定聚合根 → 版本条件更新 → 状态机守卫 → 翻转 L / A / O → 审计 + 事件。
 * 已处于目标终态则幂等 NO_CHANGE（不重复释放）；非法的"复活 / 释放已入住"一律 409。
 *
 * <p><b>统一锁序（S0-04）：</b>先锁订单（聚合根）→ 按房间 id 升序锁定<b>全部</b>相关房间 → 再写占用。
 * 锁在写占用之前一次性拿齐，且顺序固定，避免交叉持锁死锁。
 *
 * <p><b>幂等：</b>经 {@link IdempotencyService#execute} 入口；同一作用域键（含客户端幂等键）+ 同一内容
 * 只产生一次效果。提交成功但响应丢失时，重试会拿到<b>同一个订单 id</b>（resultRef）。
 *
 * <p><b>不做：</b>超时释放（S1-10）、可售查询与投影（S1-12）、订单列表/详情（S1-11）、
 * 入住/离店/换房（S3-04/05）、住客信息与渠道来源实际取值（S3-04 / L 域）。
 */
@Service
public class ReservationCommandService {

    /** 调用主体命名空间：真实 HTTP 接口用客户端的来源标识；测试固定用此值。 */
    public static final String RESERVATION_CREATE_COMMAND = "reservation.create";
    /** S1-09 状态机命令名。 */
    public static final String RESERVATION_CONFIRM_COMMAND = "reservation.confirm";
    public static final String RESERVATION_CANCEL_COMMAND = "reservation.cancel";
    public static final String RESERVATION_NOSHOW_COMMAND = "reservation.no_show";
    /** HELD 占用的默认保留时长（分钟）。超时释放由 S1-10 的 Worker 处理，这里只给一个初始截止点。 */
    private static final int HELD_TTL_MINUTES = 20;

    private final ReservationMapper reservationMapper;
    private final OccupancyService occupancyService;
    private final IdempotencyService idempotencyService;
    private final JournalService journalService;
    private final Clock clock;

    public ReservationCommandService(ReservationMapper reservationMapper, OccupancyService occupancyService,
                                    IdempotencyService idempotencyService, JournalService journalService,
                                    Clock clock) {
        this.reservationMapper = reservationMapper;
        this.occupancyService = occupancyService;
        this.idempotencyService = idempotencyService;
        this.journalService = journalService;
        this.clock = clock;
    }

    /** 命令入参：由 HTTP 层或测试构造。 */
    public record CreateCommand(UUID hotelId, String callerNamespace, String idempotencyKey,
                               ReservationSourceType sourceType, List<LineCommand> lines) {
        public record LineCommand(UUID roomTypeId, StayRange range) {
        }
    }

    /** 状态机命令入参（确认 / 取消 / No-show 共用）。{@code expectedVersion} 为 null 时跳过乐观版本校验。 */
    public record LifecycleCommand(UUID hotelId, String callerNamespace, String idempotencyKey,
                                  Long expectedVersion) {
    }

    /**
     * 创建订单（对客户端幂等）。
     *
     * @return 建好的订单视图；命中既有幂等记录时为原结果（业务未重放）
     */
    @Transactional
    public ReservationView createReservation(UUID tenantId, CreateCommand command) {
        IdempotencyService.CommandRequest request = new IdempotencyService.CommandRequest(
                tenantId, command.hotelId(), command.callerNamespace(), RESERVATION_CREATE_COMMAND,
                command.idempotencyKey(), command);
        IdempotencyService.CommandResult<ReservationView> result =
                idempotencyService.execute(request, opId -> doCreate(opId, tenantId, command));
        // 重放时不跑业务，view 为 null；用 resultRef（订单 id）补一个轻量视图
        return result.replayed()
                ? ReservationView.ofId(result.resultRef())
                : result.value();
    }

    private IdempotencyService.CommandOutcome<ReservationView> doCreate(UUID operationId, UUID tenantId,
                                                                        CreateCommand command) {
        if (command.lines().isEmpty()) {
            throw new ConflictException("订单必须至少包含一个房型需求");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID reservationId = UUID.randomUUID();
        String reservationNo = nextReservationNo(now);
        Reservation reservation = Reservation.create(reservationId, tenantId, command.hotelId(),
                reservationNo, command.sourceType(), now);
        ReservationStatus pending = ReservationStatus.PENDING;
        reservationMapper.insertReservation(reservation);

        // 1) 为每行选房（读阶段，未加锁）：排除本单已占用的房间，挑整区间空闲者
        List<UUID> chosenRooms = new ArrayList<>();
        List<ReservationConflictException.UnavailableLine> unavailable = new ArrayList<>();
        List<ReservationLine> lines = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            CreateCommand.LineCommand line = command.lines().get(i);
            UUID roomTypeId = line.roomTypeId();
            StayRange range = line.range();
            List<PhysicalRoom> free = reservationMapper.findAvailableRooms(tenantId, command.hotelId(),
                    roomTypeId, range).stream()
                    .filter(r -> !chosenRooms.contains(r.id()))
                    .toList();
            if (free.isEmpty()) {
                int available = countAvailable(tenantId, command.hotelId(), roomTypeId, range);
                unavailable.add(new ReservationConflictException.UnavailableLine(
                        (i + 1), roomTypeId, range.checkIn().toString(), range.checkOut().toString(),
                        1, available));
                continue;
            }
            PhysicalRoom pick = free.get(0);
            chosenRooms.add(pick.id());
            lines.add(ReservationLine.create(UUID.randomUUID(), tenantId, command.hotelId(), reservationId,
                    i + 1, roomTypeId, range, now));
        }
        if (!unavailable.isEmpty()) {
            throw new ReservationConflictException(unavailable);
        }

        // 2) 统一锁序：一次性、按 id 升序锁定本次要用的全部房间（已含去重与排序）
        occupancyService.lockRooms(tenantId, command.hotelId(), chosenRooms);

        // 3) 锁后重核：所选房间若在"选中→加锁"之间被别人占走，这里必然查不到，转结构化冲突
        for (int i = 0; i < lines.size(); i++) {
            UUID roomId = chosenRooms.get(i);
            ReservationLine line = lines.get(i);
            boolean stillFree = reservationMapper.findAvailableRooms(tenantId, command.hotelId(),
                    line.requestedRoomTypeId(), line.plannedRange()).stream()
                    .anyMatch(r -> r.id().equals(roomId));
            if (!stillFree) {
                throw new ReservationConflictException(List.of(
                        new ReservationConflictException.UnavailableLine(line.lineNo(),
                                line.requestedRoomTypeId(), line.plannedRange().checkIn().toString(),
                                line.plannedRange().checkOut().toString(), 1, 0)));
            }
        }

        // 4) 写订单行 + 占用（同一命令操作标识）
        OffsetDateTime expiresAt = now.plusMinutes(HELD_TTL_MINUTES);
        List<ReservationView.LineView> lineViews = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ReservationLine line = lines.get(i);
            reservationMapper.insertLine(line);
            RoomOccupancy occupancy = occupancyService.writeHeldOccupancy(operationId, tenantId,
                    command.hotelId(), reservationId, line.id(), chosenRooms.get(i), line.plannedRange(),
                    expiresAt, "订单占用");
            lineViews.add(new ReservationView.LineView(line.id(), line.requestedRoomTypeId(),
                    chosenRooms.get(i), line.plannedRange(), occupancy.id()));
        }

        // 5) 订单级审计与事件（落到同一命令操作标识）
        journalService.recordAudit(tenantId, command.hotelId(), AuditActions.RESERVATION_CREATED, "reservation",
                reservationId, null, reservation.version(),
                Map.of("lines", lines.size(), "rooms", chosenRooms.size(), "no", reservationNo));
        journalService.recordEvent(tenantId, command.hotelId(), "reservation", reservationId,
                reservation.version(), 1, "ReservationCreated",
                Map.of(                "no", reservationNo, "source", command.sourceType().name(), "lines", lines.size()));

        return new IdempotencyService.CommandOutcome<>(new ReservationView(reservationId, reservationNo,
                command.sourceType(), pending, lineViews), reservationId,
                Map.of("reservationId", reservationId, "no", reservationNo));
    }

    private int countAvailable(UUID tenantId, UUID hotelId, UUID roomTypeId, StayRange range) {
        return (int) reservationMapper.findAvailableRooms(tenantId, hotelId, roomTypeId, range).stream()
                .map(PhysicalRoom::id)
                .distinct()
                .count();
    }

    /** 订单号：固定前缀 + 时间戳 + 随机，满足 V4 的 CHECK（字母数字、长度 3–32）。 */
    private String nextReservationNo(OffsetDateTime now) {
        String stamp = now.toLocalDate().toString().replace("-", "") + String.format("%02d", now.getHour())
                + String.format("%02d", now.getMinute());
        String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        return "RSV" + stamp + rand;
    }

    // ===================== 状态机命令（S1-09） =====================

    /** 确认：整单 PENDING → CONFIRMED（占用 HELD → CONFIRMED，保留占用，不释放）。 */
    @Transactional
    public ReservationView confirmReservation(UUID tenantId, UUID reservationId, LifecycleCommand command) {
        return runLifecycle(tenantId, reservationId, command, RESERVATION_CONFIRM_COMMAND, LifecycleKind.CONFIRM);
    }

    /** 取消：整单 PENDING/CONFIRMED → CANCELLED（占用 → RELEASED）。已入住不可经此释放。 */
    @Transactional
    public ReservationView cancelReservation(UUID tenantId, UUID reservationId, LifecycleCommand command) {
        return runLifecycle(tenantId, reservationId, command, RESERVATION_CANCEL_COMMAND, LifecycleKind.CANCEL);
    }

    /** No-show：整单 CONFIRMED → NO_SHOW（占用 → RELEASED）。 */
    @Transactional
    public ReservationView markNoShow(UUID tenantId, UUID reservationId, LifecycleCommand command) {
        return runLifecycle(tenantId, reservationId, command, RESERVATION_NOSHOW_COMMAND, LifecycleKind.NO_SHOW);
    }

    private ReservationView runLifecycle(UUID tenantId, UUID reservationId, LifecycleCommand command,
                                        String commandName, LifecycleKind kind) {
        IdempotencyService.CommandRequest request = new IdempotencyService.CommandRequest(
                tenantId, command.hotelId(), command.callerNamespace(), commandName,
                command.idempotencyKey(), command);
        IdempotencyService.CommandResult<ReservationView> result =
                idempotencyService.execute(request, opId -> doLifecycle(opId, tenantId, reservationId, command, kind));
        // 重放时不跑业务，view 为 null；用库内最新状态补一个视图
        return result.replayed() ? buildView(tenantId, command.hotelId(), reservationId) : result.value();
    }

    private IdempotencyService.CommandOutcome<ReservationView> doLifecycle(UUID operationId, UUID tenantId,
                                                                          UUID reservationId,
                                                                          LifecycleCommand command,
                                                                          LifecycleKind kind) {
        // 1) 锁聚合根（FOR UPDATE）→ 全单状态机命令都先锁 O，并发天然串行化
        Reservation reservation = occupancyService.requireReservation(tenantId, command.hotelId(), reservationId);
        // 2) 乐观版本条件：非 null 时强制核对，避免用过期客户端的版本覆盖
        if (command.expectedVersion() != null && !command.expectedVersion().equals(reservation.version())) {
            throw new ConflictException("订单版本不匹配（期望 " + command.expectedVersion()
                    + "，实际 " + reservation.version() + "）");
        }
        List<ReservationLine> lines = reservationMapper.findLinesByReservation(tenantId, command.hotelId(), reservationId);
        if (lines.isEmpty()) {
            throw new ConflictException("订单没有任何订单行");
        }
        ReservationStatus from = reservation.status();
        // 3) 已处于目标终态 → 幂等 NO_CHANGE：不再翻转、不再释放（重复取消/确认不会二次生效）
        if (from == kind.resTarget) {
            return new IdempotencyService.CommandOutcome<>(buildView(tenantId, command.hotelId(), reservationId),
                    reservationId, Map.of("reservationId", reservationId, "status", from.name(), "noChange", true));
        }
        // 4) 状态机守卫：非法迁移（如确认已取消、取消已入住）直接拒绝
        String guardError = kind.guard.apply(lines.stream().map(ReservationLine::status).toList());
        if (guardError != null) {
            throw new ConflictException(guardError);
        }
        // 5) 统一锁序：聚合根已锁，再按 id 升序锁全部相关房间（防御性，占用翻转前拿齐资源）
        List<RoomOccupancy> occupancies =
                reservationMapper.findOccupanciesByReservation(tenantId, command.hotelId(), reservationId);
        List<UUID> roomIds = occupancies.stream().map(RoomOccupancy::roomId).distinct().sorted().toList();
        if (!roomIds.isEmpty()) {
            occupancyService.lockRooms(tenantId, command.hotelId(), roomIds);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        // 6) 推进订单行
        for (ReservationLine line : lines) {
            reservationMapper.updateLineStatus(tenantId, command.hotelId(), line.id(), kind.lineTarget, now);
        }
        // 7) 推进占用：仅翻转"仍在 from 集合"的有效占用（HELD→CONFIRMED 不释放；→RELEASED 才释放一次）
        for (RoomOccupancy occ : occupancies) {
            if (kind.occFrom.contains(occ.status()) && occ.status() != kind.occTarget) {
                occupancyService.transitionStatus(operationId, tenantId, command.hotelId(), occ.id(), kind.occTarget);
            }
        }
        // 8) 推进聚合根状态 + 递增版本
        reservationMapper.updateReservationStatus(tenantId, command.hotelId(), reservationId, kind.resTarget, now);
        // 9) 订单级审计与事件（落到同一命令操作标识）
        journalService.recordAudit(tenantId, command.hotelId(), kind.auditAction, "reservation", reservationId,
                reservation.version(), reservation.version() + 1L,
                Map.of("from", from.name(), "to", kind.resTarget.name(), "lines", lines.size()));
        journalService.recordEvent(tenantId, command.hotelId(), "reservation", reservationId,
                reservation.version() + 1L, 1, kind.eventName,
                Map.of("from", from.name(), "to", kind.resTarget.name(), "lines", lines.size()));

        return new IdempotencyService.CommandOutcome<>(buildView(tenantId, command.hotelId(), reservationId),
                reservationId, Map.of("reservationId", reservationId, "status", kind.resTarget.name()));
    }

    /** 由库内最新状态重建对外视图（重放 / NO_CHANGE / 命令后都用它）。 */
    private ReservationView buildView(UUID tenantId, UUID hotelId, UUID reservationId) {
        Reservation r = reservationMapper.findReservation(tenantId, hotelId, reservationId)
                .orElseThrow(() -> new NotFoundException("订单不存在：" + reservationId));
        List<ReservationLine> lines = reservationMapper.findLinesByReservation(tenantId, hotelId, reservationId);
        List<RoomOccupancy> occupancies = reservationMapper.findOccupanciesByReservation(tenantId, hotelId, reservationId);
        Map<UUID, RoomOccupancy> byLine = occupancies.stream()
                .collect(Collectors.toMap(RoomOccupancy::lineId, o -> o, (a, b) -> a));
        List<ReservationView.LineView> lineViews = lines.stream().map(line -> {
            RoomOccupancy occ = byLine.get(line.id());
            return new ReservationView.LineView(line.id(), line.requestedRoomTypeId(),
                    occ == null ? null : occ.roomId(), line.plannedRange(),
                    occ == null ? null : occ.id());
        }).toList();
        return new ReservationView(r.id(), r.reservationNo(), r.sourceType(), r.status(), lineViews);
    }

    /** 状态机命令的静态定义：守卫、目标状态、占用翻转集合与审计/事件名。 */
    private enum LifecycleKind {
        CONFIRM(ReservationTransitions::confirmError, LineStatus.CONFIRMED,
                EnumSet.of(OccupancyStatus.HELD), OccupancyStatus.CONFIRMED,
                ReservationStatus.CONFIRMED, AuditActions.RESERVATION_CONFIRMED, "ReservationConfirmed"),
        CANCEL(ReservationTransitions::cancelError, LineStatus.CANCELLED,
                EnumSet.of(OccupancyStatus.HELD, OccupancyStatus.CONFIRMED), OccupancyStatus.RELEASED,
                ReservationStatus.CANCELLED, AuditActions.RESERVATION_CANCELLED, "ReservationCancelled"),
        NO_SHOW(ReservationTransitions::noShowError, LineStatus.NO_SHOW,
                EnumSet.of(OccupancyStatus.HELD, OccupancyStatus.CONFIRMED), OccupancyStatus.RELEASED,
                ReservationStatus.NO_SHOW, AuditActions.RESERVATION_NO_SHOW, "ReservationNoShow");

        final Function<List<LineStatus>, String> guard;
        final LineStatus lineTarget;
        final Set<OccupancyStatus> occFrom;
        final OccupancyStatus occTarget;
        final ReservationStatus resTarget;
        final String auditAction;
        final String eventName;

        LifecycleKind(Function<List<LineStatus>, String> guard, LineStatus lineTarget,
                      Set<OccupancyStatus> occFrom, OccupancyStatus occTarget, ReservationStatus resTarget,
                      String auditAction, String eventName) {
            this.guard = guard;
            this.lineTarget = lineTarget;
            this.occFrom = occFrom;
            this.occTarget = occTarget;
            this.resTarget = resTarget;
            this.auditAction = auditAction;
            this.eventName = eventName;
        }
    }
}
