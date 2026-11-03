package cn.sec.hn.openbackupsaas.reservation;

import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationConflictException;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationCommandService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-08 原子建单集成测试：**必须连真实 PostgreSQL**。
 *
 * <p>要证明的不是"能插进去"，而是：
 * 多房多晚整体原子、任一房型挑不到就整单回滚、同一幂等键重放拿回同一订单、
 * 并发热点只有一个赢家、日期非法被拒。
 */
class ReservationCommandIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDate IN = LocalDate.of(2026, 4, 1);
    private static final LocalDate OUT = LocalDate.of(2026, 4, 4); // 3 晚

    @Autowired
    private ReservationCommandService reservationCommandService;
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private RoomService roomService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void multiRoomMultiNightIsAtomicAndIdempotentWithinOneCommand() {
        Fixture f = fixture("a1", 2);
        ReservationCommandService.CreateCommand command = new ReservationCommandService.CreateCommand(
                f.hotelId(), "test-client", "key-a1", ReservationSourceType.FRONTDESK,
                List.of(
                        new ReservationCommandService.CreateCommand.LineCommand(f.typeA(), StayRange.of(IN, OUT)),
                        new ReservationCommandService.CreateCommand.LineCommand(f.typeA(), StayRange.of(IN, OUT))));

        var view = reservationCommandService.createReservation(f.tenantId(), command);

        // 两间房、三晚：占用 2 行、流水 3×2=6 行
        assertEquals(1, count("reservation"));
        assertEquals(2, count("reservation_line"));
        assertEquals(2, count("room_occupancy"));
        assertEquals(6, count("inventory_ledger"));
        assertTrue(count("outbox_event") >= 1, "订单创建事件应已写入");
        assertTrue(count("audit_log") >= 2, "占用审计 + 订单审计应已写入");
        assertTrue(view.id() != null);
    }

    @Test
    void anyLineUnavailableRollsBackWholeOrder() {
        // 房型 A 有 2 间，房型 B 一间都没有 → 第二行无法满足 → 整单回滚
        Fixture f = fixture("a2", 2);
        RoomType typeB = roomService.createRoomType(f.tenantId(), f.hotelId(), "deluxe", "豪华间", 2, 0, null);

        ReservationCommandService.CreateCommand command = new ReservationCommandService.CreateCommand(
                f.hotelId(), "test-client", "key-a2", ReservationSourceType.DIRECT,
                List.of(
                        new ReservationCommandService.CreateCommand.LineCommand(f.typeA(), StayRange.of(IN, OUT)),
                        new ReservationCommandService.CreateCommand.LineCommand(typeB.id(), StayRange.of(IN, OUT))));

        ReservationConflictException conflict = assertThrows(ReservationConflictException.class,
                () -> reservationCommandService.createReservation(f.tenantId(), command));
        assertEquals(1, conflict.lineConflicts().size(), "应只报告无法满足的那一行");
        assertEquals(typeB.id(), conflict.lineConflicts().get(0).roomTypeId());
        assertEquals(0, conflict.lineConflicts().get(0).available(), "该房型可用间数应为 0");

        // 整单回滚：一张表都不该留下
        assertEquals(0, count("reservation"));
        assertEquals(0, count("reservation_line"));
        assertEquals(0, count("room_occupancy"));
        assertEquals(0, count("inventory_ledger"));
        assertEquals(0, count("audit_log"));
    }

    @Test
    void sameIdempotencyKeyReplaysOriginalReservation() {
        Fixture f = fixture("a3", 2);
        ReservationCommandService.CreateCommand command = new ReservationCommandService.CreateCommand(
                f.hotelId(), "test-client", "replay-key", ReservationSourceType.FRONTDESK,
                List.of(
                        new ReservationCommandService.CreateCommand.LineCommand(f.typeA(), StayRange.of(IN, OUT)),
                        new ReservationCommandService.CreateCommand.LineCommand(f.typeA(), StayRange.of(IN, OUT))));

        var first = reservationCommandService.createReservation(f.tenantId(), command);
        var second = reservationCommandService.createReservation(f.tenantId(), command);

        assertEquals(first.id(), second.id(), "重放应拿回同一个订单 id");
        assertEquals(1, count("reservation"), "重放不应再建订单");
        assertEquals(2, count("room_occupancy"), "占用仍只有一轮");
    }

    @Test
    void concurrentHotspotLeavesExactlyOneWinner() throws Exception {
        // 仅 1 间房，两个并发建单抢它：一个成功、一个结构化冲突，不死锁、不双卖
        Fixture f = fixture("a4", 1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> first = () -> attemptCreate(f, "c1");
            Callable<Boolean> second = () -> attemptCreate(f, "c2");
            Future<Boolean> r1 = pool.submit(first);
            Future<Boolean> r2 = pool.submit(second);
            boolean ok1 = r1.get(30, TimeUnit.SECONDS);
            boolean ok2 = r2.get(30, TimeUnit.SECONDS);
            assertEquals(1, (ok1 ? 1 : 0) + (ok2 ? 1 : 0), "两个并发抢一间房，必须只有一个成功");
            assertEquals(1, count("reservation"), "只应留下一个订单");
            assertEquals(1, count("room_occupancy"), "只应占用一间房");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reversedDateRangeIsRejected() {
        Fixture f = fixture("a5", 1);
        // StayRange 自身拒绝：离店日不晚于入住日
        assertThrows(IllegalArgumentException.class, () -> StayRange.of(OUT, IN));
        assertThrows(IllegalArgumentException.class, () -> StayRange.of(IN, IN));
    }

    private boolean attemptCreate(Fixture f, String key) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        try {
            return Boolean.TRUE.equals(template.execute(status -> {
                ReservationCommandService.CreateCommand command =
                        new ReservationCommandService.CreateCommand(f.hotelId(), "test-client", key,
                                ReservationSourceType.FRONTDESK,
                                List.of(new ReservationCommandService.CreateCommand.LineCommand(
                                        f.typeA(), StayRange.of(IN, OUT))));
                reservationCommandService.createReservation(f.tenantId(), command);
                return Boolean.TRUE;
            }));
        } catch (RuntimeException ex) {
            // 冲突（409）或锁等待被对方占先都算"没成功"
            return false;
        }
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private Fixture fixture(String suffix, int roomsOfTypeA) {
        Tenant tenant = organizationService.createTenant("t-" + suffix, "建单测试租户 " + suffix);
        Hotel hotel = organizationService.createHotel(tenant.id(), null, "h" + suffix, "主店", "Asia/Shanghai");
        RoomType typeA = roomService.createRoomType(tenant.id(), hotel.id(), "std", "标准间", 2, 0, null);
        for (int i = 0; i < roomsOfTypeA; i++) {
            roomService.createRoom(tenant.id(), hotel.id(), typeA.id(), "0" + (i + 1) + suffix, i + 1, null);
        }
        return new Fixture(tenant.id(), hotel.id(), typeA.id());
    }

    private record Fixture(UUID tenantId, UUID hotelId, UUID typeA) {
    }
}
