package cn.sec.hn.openbackupsaas.reservation.service;

import cn.sec.hn.openbackupsaas.command.domain.RequestDigest;
import cn.sec.hn.openbackupsaas.command.service.IdempotencyService;
import cn.sec.hn.openbackupsaas.journal.domain.AuditActions;
import cn.sec.hn.openbackupsaas.journal.service.JournalService;
import cn.sec.hn.openbackupsaas.reservation.domain.DueReservation;
import cn.sec.hn.openbackupsaas.reservation.domain.HoldDueState;
import cn.sec.hn.openbackupsaas.reservation.domain.LineStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.OccupancyStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.Reservation;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationLine;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.RoomOccupancy;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationExpiryMapper;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 锁房超时承接（S1-10）：把过了 {@code expires_at} 仍未确认的 HELD 占用按订单整单释放。
 *
 * <p><b>为什么"到期"不等于"已释放"（最重要的语义）：</b>
 * {@code expires_at} 只是一个<b>事实字段</b>，不是状态机的一部分。超过截止时间但状态仍是
 * HELD 的占用<b>依然有效</b>、依然参与互斥、依然挡住别人的预订——因为"过期"只是一条时间信息，
 * 真正让房间重新可售的是本类写下的事务化状态迁移。这与 V4 的设计一致：
 * "超过截止并不自动释放，必须由事务化 ExpireHold 改写状态（S1-10）"。
 *
 * <p><b>一次批处理 = 一个事务。</b>
 * 领取（{@code FOR UPDATE SKIP LOCKED}）的锁必须持到写入完成，否则"领取"就退化成
 * "看了一眼"。因此本类不做"先领一批再逐单开事务"。整批同生共死：批内任一单异常会让
 * 整批回滚，下一轮扫描会重新领到这些行——<b>回滚是正确的恢复路径</b>，因为到期条件
 * 仍然成立，且没有任何半完成状态被提交。
 *
 * <p><b>多 Worker 只释放一次：</b>行锁由 {@code SKIP LOCKED} 分给一个 Worker；
 * 万一两个 Worker 都看到同一行（例如不同批次的边界），{@link ReservationExpiryMapper#expireIfPending}
 * 的 {@code WHERE status = 'PENDING'} 保证只有一个能真正迁移，另一个拿到 0 行后跳过。
 *
 * <p><b>时间基准：</b>到期判定只用数据库 {@code now()}（见 Mapper XML）；
 * 这里的时间戳只用于写 {@code updated_at} 与流水，不参与"是否到期"的判断。
 *
 * <p><b>不做：</b>已确认单超时（DEC-08 讨论范围）、部分履约（DEC-04）、
 * 订单列表/详情（S1-11）、可售投影（S1-12）、通知与渠道同步（S2-01 之后）。
 */
@Service
public class ReservationExpiryService {

    /** 系统路径的命令名：到期释放不是客户端的命令，不可被客户端幂等键重放。 */
    public static final String RESERVATION_EXPIRE_COMMAND = "reservation.expire";

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryService.class);

    private final ReservationExpiryMapper expiryMapper;
    private final ReservationMapper reservationMapper;
    private final OccupancyService occupancyService;
    private final IdempotencyService idempotencyService;
    private final JournalService journalService;
    private final Clock clock;

    public ReservationExpiryService(ReservationExpiryMapper expiryMapper, ReservationMapper reservationMapper,
                                    OccupancyService occupancyService, IdempotencyService idempotencyService,
                                    JournalService journalService, Clock clock) {
        this.expiryMapper = expiryMapper;
        this.reservationMapper = reservationMapper;
        this.occupancyService = occupancyService;
        this.idempotencyService = idempotencyService;
        this.journalService = journalService;
        this.clock = clock;
    }

    /**
     * 跑一轮到期扫描（可被调度器或多个 Worker 并发调用）。
     *
     * @param batchSize 本批上限；必须为正数，避免误传 0 变成"永远不释放"
     * @return 本轮报告（供日志与运维观测，不对外暴露 HTTP）
     */
    @Transactional
    public ExpiryReport expireDueHolds(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("批大小必须为正数，收到 " + batchSize);
        }
        List<DueReservation> due = expiryMapper.claimDueReservations(batchSize);
        int expired = 0;
        int skipped = 0;
        for (DueReservation candidate : due) {
            if (expireOne(candidate)) {
                expired++;
            } else {
                skipped++;
            }
        }
        if (!due.isEmpty()) {
            log.info("到期承接扫描：领取 {} 单，释放 {} 单，跳过 {} 单", due.size(), expired, skipped);
        }
        return new ExpiryReport(due.size(), expired, skipped);
    }

    /**
     * 处理一单。<b>不使用 {@code TenantContextHolder}：</b>Worker 线程没有可归属的登录人，
     * 伪造一个 {@code system} 账号只会让审计里出现一个不存在的用户。
     * 租户来自这一行自己的 {@code tenant_id} 列（不是全局值、不是请求体），
     * 且后续每条 SQL 都显式带上它；审计因此如实记为 SYSTEM（见 {@code JournalService#currentActor}）。
     *
     * @return true 表示本单已被本方法迁移到 EXPIRED；false 表示"轮不到我处理"
     */
    private boolean expireOne(DueReservation candidate) {
        UUID tenantId = candidate.tenantId();
        UUID hotelId = candidate.hotelId();
        UUID reservationId = candidate.id();

        // 1) 重读聚合根（行锁已由领取语句持有）：不用扫描时的快照，且顺手拿到最新版本
        Reservation reservation = occupancyService.requireReservation(tenantId, hotelId, reservationId);
        if (reservation.status() != ReservationStatus.PENDING) {
            return false;
        }

        // 2) 二次校验"是否真的该释放"：全部 HELD 都已到期，且至少有一条 HELD。
        //    用数据库时钟复核，而不是复用领取时的判断——扫描与本方法之间隔了别的行处理，
        //    判断必须能被独立验证。
        HoldDueState state = expiryMapper.checkHoldDue(tenantId, hotelId, reservationId);
        if (!state.due()) {
            return false;
        }

        List<ReservationLine> lines = reservationMapper.findLinesByReservation(tenantId, hotelId, reservationId);
        List<RoomOccupancy> occupancies =
                reservationMapper.findOccupanciesByReservation(tenantId, hotelId, reservationId);

        // 3) 统一锁序（S0-04）：聚合根已锁，再按房间 id 升序锁全部相关房间
        List<UUID> roomIds = occupancies.stream().map(RoomOccupancy::roomId).distinct().sorted().toList();
        if (!roomIds.isEmpty()) {
            occupancyService.lockRooms(tenantId, hotelId, roomIds);
        }

        // 4) 条件迁移聚合状态：0 行表示已不是 PENDING（别的命令先完成），此时不得再动子资源
        if (expiryMapper.expireIfPending(tenantId, hotelId, reservationId) == 0) {
            return false;
        }

        UUID operationId = idempotencyService.beginInternal(tenantId, hotelId, RESERVATION_EXPIRE_COMMAND,
                RequestDigest.sha256Hex(Map.of("reservationId", reservationId.toString(),
                        "reason", "HOLD_TIMEOUT")));
        OffsetDateTime now = OffsetDateTime.now(clock);

        // 5) 推进订单行与占用：占用 HELD → EXPIRED（退出有效集合，房间随之可再售）
        for (ReservationLine line : lines) {
            reservationMapper.updateLineStatus(tenantId, hotelId, line.id(), LineStatus.EXPIRED, now);
        }
        for (RoomOccupancy occ : occupancies) {
            if (occ.status() == OccupancyStatus.HELD) {
                occupancyService.transitionStatus(operationId, tenantId, hotelId, occ.id(), OccupancyStatus.EXPIRED);
            }
        }

        // 6) 审计与事件（同一 operation_id，和释放流水同事务）
        long fromVersion = reservation.version();
        Map<String, Object> summary = Map.of("from", ReservationStatus.PENDING.name(),
                "to", ReservationStatus.EXPIRED.name(), "reason", "HOLD_TIMEOUT",
                "lines", lines.size(), "releasedOccupancies", state.heldCount());
        journalService.recordAudit(tenantId, hotelId, AuditActions.RESERVATION_EXPIRED, "reservation",
                reservationId, fromVersion, fromVersion + 1L, summary);
        journalService.recordEvent(tenantId, hotelId, "reservation", reservationId, fromVersion + 1L, 1,
                "ReservationExpired", summary);
        idempotencyService.succeed(tenantId, operationId, reservationId,
                Map.of("reservationId", reservationId, "status", ReservationStatus.EXPIRED.name()));
        return true;
    }

    /** 一轮扫描的结果。字段刻意只给计数：到期承接没有"部分成功"这种对外结论。 */
    public record ExpiryReport(int scanned, int expired, int skipped) {
    }
}
