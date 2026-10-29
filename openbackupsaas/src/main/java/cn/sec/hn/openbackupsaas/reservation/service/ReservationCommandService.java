package cn.sec.hn.openbackupsaas.reservation.service;

import cn.sec.hn.openbackupsaas.command.service.IdempotencyService;
import cn.sec.hn.openbackupsaas.journal.domain.AuditActions;
import cn.sec.hn.openbackupsaas.journal.service.JournalService;
import cn.sec.hn.openbackupsaas.reservation.domain.Reservation;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationConflictException;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationLine;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.RoomOccupancy;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationMapper;
import cn.sec.hn.openbackupsaas.reservation.web.ReservationView;
import cn.sec.hn.openbackupsaas.room.domain.PhysicalRoom;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 创建订单命令（S1-08）：把"建单 + 锁房 + 写占用"收进<b>一个短事务</b>，并对客户端幂等。
 *
 * <p><b>原子性：</b>多房多晚要么全成、要么全败。任一房型在所选区间挑不到可用房间，
 * 整单在业务层抛出结构化冲突（409），事务回滚，不留下"部分房间被占"的半截状态。
 *
 * <p><b>统一锁序（S0-04）：</b>先插订单（聚合根）→ 按房间 id 升序锁定<b>全部</b>本次要用的房间
 * → 再写任何占用。锁在写占用之前一次性拿齐，且顺序固定，避免两个并发建单交叉持锁死锁。
 * 锁定后还会对所选房间<b>重新核一遍</b>可用性，把"读阶段选中、锁之前被别人抢走"的竞态在业务层就挡成
 * 结构化冲突，而不是放任到 EXCLUDE 约束才报含糊错误。
 *
 * <p><b>幂等：</b>经 {@link IdempotencyService#execute} 入口；同一作用域键（含客户端幂等键）+ 同一内容
 * 只产生一次效果。提交成功但响应丢失时，重试会拿到<b>同一个订单 id</b>（resultRef）。
 *
 * <p><b>不做：</b>确认/取消/No-show（S1-09）、超时释放（S1-10）、可售查询与投影（S1-12）、
 * 订单列表（S1-11）、住客信息与渠道来源实际取值（S3-04 / L 域）。
 */
@Service
public class ReservationCommandService {

    /** 调用主体命名空间：真实 HTTP 接口用客户端的来源标识；测试固定用此值。 */
    public static final String RESERVATION_CREATE_COMMAND = "reservation.create";
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
}
