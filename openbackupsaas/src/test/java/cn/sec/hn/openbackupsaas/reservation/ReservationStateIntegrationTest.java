package cn.sec.hn.openbackupsaas.reservation;

import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationMapper;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationCommandService;
import cn.sec.hn.openbackupsaas.reservation.web.ReservationView;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-09 状态机命令集成测试：**必须连真实 PostgreSQL**。
 *
 * <p>要证明的不是"能改状态"，而是 S1-09 的四个验收点：
 * 确认与取消并发只有合法迁移生效；重复取消不重复释放；取消后旧确认不复活；
 * 已入住订单不能被普通取消接口直接释放。外加 No-show 路径与并发安全。
 */
class ReservationStateIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDate IN = LocalDate.of(2026, 5, 1);
    private static final LocalDate OUT = LocalDate.of(2026, 5, 4); // 3 晚

    @Autowired
    private ReservationCommandService reservationCommandService;
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private RoomService roomService;
    @Autowired
    private ReservationMapper reservationMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void confirmMovesPendingToConfirmedAndKeepsOccupancy() {
        Fixture f = fixture("s1", 2);
        UUID id = createPending(f).id();

        ReservationView view = confirm(f, id, "confirm-1");
        assertEquals(ReservationStatus.CONFIRMED, view.status());

        // 占用从 HELD → CONFIRMED（仍有效，未释放）
        assertEquals(1, count("room_occupancy WHERE reservation_id = ? AND status = 'CONFIRMED'", id));
        assertEquals(0, count("room_occupancy WHERE reservation_id = ? AND status = 'RELEASED'", id));
        // 订单版本应递增
        assertEquals(1L, versionOf(id));
        int auditRows = jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log", Integer.class);
        assertTrue(auditRows >= 1, "确认审计应已写入");
    }

    @Test
    void cancelReleasesOccupancyAndRoomBecomesAvailable() {
        Fixture f = fixture("s2", 1);
        ReservationView created = createPending(f);
        UUID roomId = created.lines().get(0).assignedRoomId();

        ReservationView view = cancel(f, created.id(), "cancel-1");
        assertEquals(ReservationStatus.CANCELLED, view.status());

        // 占用 → RELEASED（退出有效集合），房间随之可再售
        assertEquals(1, count("room_occupancy WHERE reservation_id = ? AND status = 'RELEASED'", created.id()));
        List<UUID> available = reservationMapper.findAvailableRooms(f.tenantId(), f.hotelId(), f.typeA(),
                StayRange.of(IN, OUT)).stream().map(r -> r.id()).toList();
        assertTrue(available.contains(roomId), "取消后该房间应重新可售");
    }

    @Test
    void repeatedCancelDoesNotDoubleRelease() {
        Fixture f = fixture("s3", 1);
        UUID id = createPending(f).id();

        cancel(f, id, "cancel-first");
        cancel(f, id, "cancel-second"); // 不同幂等键，但已处于 CANCELLED → 幂等 NO_CHANGE
        cancel(f, id, "cancel-third");

        // 只有一行占用，且仍是 RELEASED（重复取消未再插入 / 再释放）
        assertEquals(1, count("room_occupancy WHERE reservation_id = ?", id));
        assertEquals(1, count("room_occupancy WHERE reservation_id = ? AND status = 'RELEASED'", id));
    }

    @Test
    void cancelAfterConfirmDoesNotReviveOnLateConfirm() {
        Fixture f = fixture("s4", 1);
        UUID id = createPending(f).id();
        confirm(f, id, "confirm-1");
        cancel(f, id, "cancel-1");
        assertEquals(ReservationStatus.CANCELLED, statusOf(id));

        // 取消后收到"旧确认" → 应当被拒绝（终态不复活）
        assertThrows(ConflictException.class, () -> confirm(f, id, "confirm-late"));
        assertEquals(ReservationStatus.CANCELLED, statusOf(id), "订单不应被旧确认复活");
    }

    @Test
    void checkedInOrderCannotBeReleasedByNormalCancel() {
        Fixture f = fixture("s5", 1);
        UUID id = createPending(f).id();
        // 模拟已由 S3-04 入住（本任务不实现 CheckIn，直接置状态以构造已入住场景）
        jdbcTemplate.update("UPDATE reservation SET status = 'CHECKED_IN', version = version + 1 WHERE id = ?", id);
        jdbcTemplate.update("UPDATE reservation_line SET status = 'CHECKED_IN', version = version + 1 WHERE reservation_id = ?", id);
        jdbcTemplate.update("UPDATE room_occupancy SET status = 'CHECKED_IN', version = version + 1 WHERE reservation_id = ?", id);

        // 普通取消接口应拒绝已入住订单
        assertThrows(ConflictException.class, () -> cancel(f, id, "cancel-checkedin"));
        assertEquals("CHECKED_IN", occupancyStatusOf(id), "占用不应被普通取消释放，仍为 CHECKED_IN");
        assertEquals(0, count("room_occupancy WHERE reservation_id = ? AND status = 'RELEASED'", id));
    }

    @Test
    void noShowRequiresConfirmedAndReleasesOccupancy() {
        Fixture f = fixture("s6", 1);
        UUID id = createPending(f).id();
        // No-show 要求已确认：未确认直接标记应被拒
        assertThrows(ConflictException.class, () -> noShow(f, id, "noshow-early"));

        confirm(f, id, "confirm-1");
        ReservationView view = noShow(f, id, "noshow-1");
        assertEquals(ReservationStatus.NO_SHOW, view.status());
        assertEquals(1, count("room_occupancy WHERE reservation_id = ? AND status = 'RELEASED'", id));
    }

    @Test
    void concurrentConfirmAndCancelOnlyApplyLegalTransition() throws Exception {
        // PENDING 订单上并发 confirm 与 cancel：无论谁先，最终都是合法终态，且占用只一份、不重复释放
        Fixture f = fixture("s7", 1);
        UUID id = createPending(f).id();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        int rejected = 0;
        try {
            Callable<Boolean> confirmTask = () -> runInTx(() -> {
                confirm(f, id, "concurrent-confirm");
                return Boolean.TRUE;
            });
            Callable<Boolean> cancelTask = () -> runInTx(() -> {
                cancel(f, id, "concurrent-cancel");
                return Boolean.TRUE;
            });
            Future<Boolean> r1 = pool.submit(confirmTask);
            Future<Boolean> r2 = pool.submit(cancelTask);
            rejected += awaitAllowingConflict(r1);
            rejected += awaitAllowingConflict(r2);
        } finally {
            pool.shutdownNow();
        }

        // 确定结论：cancel 在 confirm 之后永远合法（PENDING/CONFIRMED 都可取消），
        // 而 confirm 在 cancel 之后因终态被拒 → 最终一律 CANCELLED + RELEASED。
        assertTrue(rejected <= 1, "并发的确认与取消至多一个被拒绝，实际被拒 " + rejected + " 个");
        assertEquals(ReservationStatus.CANCELLED, statusOf(id));
        assertEquals(1, count("room_occupancy WHERE reservation_id = ?", id));
        assertEquals(1, count("room_occupancy WHERE reservation_id = ? AND status = 'RELEASED'", id));
    }

    // ===================== 辅助 =====================

    /**
     * 等待并发任务：成功返回 0；被状态机拒绝（409）返回 1；其他异常原样抛出。
     *
     * <p>并发用例不能假定谁先拿到聚合根的行锁：cancel 抢先则 confirm 因终态被拒，
     * confirm 抢先则两者都成功（PENDING/CONFIRMED 均可取消）。两种都是合法结果，
     * 测试只固定"最终状态"，不固定时序——否则用例会变成随调度抖动的假并发测试。
     */
    private int awaitAllowingConflict(Future<Boolean> future) throws Exception {
        try {
            future.get(30, TimeUnit.SECONDS);
            return 0;
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof ConflictException) {
                return 1;
            }
            throw ex;
        }
    }

    private ReservationView createPending(Fixture f) {
        ReservationCommandService.CreateCommand command = new ReservationCommandService.CreateCommand(
                f.hotelId(), "test-client", "create-" + f.suffix(), ReservationSourceType.FRONTDESK,
                List.of(new ReservationCommandService.CreateCommand.LineCommand(f.typeA(), StayRange.of(IN, OUT))));
        return reservationCommandService.createReservation(f.tenantId(), command);
    }

    private ReservationView confirm(Fixture f, UUID id, String key) {
        return reservationCommandService.confirmReservation(f.tenantId(), id,
                new ReservationCommandService.LifecycleCommand(f.hotelId(), "test-client", key, null));
    }

    private ReservationView cancel(Fixture f, UUID id, String key) {
        return reservationCommandService.cancelReservation(f.tenantId(), id,
                new ReservationCommandService.LifecycleCommand(f.hotelId(), "test-client", key, null));
    }

    private ReservationView noShow(Fixture f, UUID id, String key) {
        return reservationCommandService.markNoShow(f.tenantId(), id,
                new ReservationCommandService.LifecycleCommand(f.hotelId(), "test-client", key, null));
    }

    private Boolean runInTx(Callable<Boolean> work) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }));
    }

    private ReservationStatus statusOf(UUID id) {
        return ReservationStatus.valueOf(
                jdbcTemplate.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id));
    }

    private String occupancyStatusOf(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM room_occupancy WHERE reservation_id = ?", String.class, id);
    }

    private long versionOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT version FROM reservation WHERE id = ?", Long.class, id);
    }

    private int count(String where, UUID id) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + where, Integer.class, id);
    }

    private Fixture fixture(String suffix, int roomsOfTypeA) {
        Tenant tenant = organizationService.createTenant("t-" + suffix, "状态机测试租户 " + suffix);
        Hotel hotel = organizationService.createHotel(tenant.id(), null, "h" + suffix, "主店", "Asia/Shanghai");
        RoomType typeA = roomService.createRoomType(tenant.id(), hotel.id(), "std", "标准间", 2, 0, null);
        for (int i = 0; i < roomsOfTypeA; i++) {
            roomService.createRoom(tenant.id(), hotel.id(), typeA.id(), "0" + (i + 1) + suffix, i + 1, null);
        }
        return new Fixture(tenant.id(), hotel.id(), typeA.id(), suffix);
    }

    private record Fixture(UUID tenantId, UUID hotelId, UUID typeA, String suffix) {
    }
}
