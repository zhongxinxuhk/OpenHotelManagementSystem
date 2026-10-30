package cn.sec.hn.openbackupsaas.reservation;

import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationExpiryMapper;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationMapper;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationCommandService;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationExpiryService;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationExpiryService.ExpiryReport;
import cn.sec.hn.openbackupsaas.reservation.web.ReservationView;
import cn.sec.hn.openbackupsaas.room.domain.PhysicalRoom;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-10 到期承接集成测试：**必须连真实 PostgreSQL**。
 *
 * <p>要证明的是 S1-10 的四个验收点，不是"能改状态"：
 * <ol>
 *   <li>两个 Worker 重复扫描只释放一次；</li>
 *   <li>确认与到期任务竞争的结果符合状态机，且订单/占用联合状态一致；</li>
 *   <li>任务中断不留半完成库存，且下一轮能重新领取；</li>
 *   <li>仅时间到期、尚未完成状态迁移的记录<b>仍计有效占用</b>。</li>
 * </ol>
 *
 * <p>到期时间一律用数据库 {@code now()} 造（{@code now() - interval}），
 * 因为生产判定也用数据库时钟；用应用时钟造"过去"会让测试与实现走两套时间基准，
 * 那种测试在时钟漂移时先红后绿，什么也证明不了。
 */
class ReservationExpiryIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDate IN = LocalDate.of(2026, 5, 1);
    private static final LocalDate OUT = LocalDate.of(2026, 5, 3); // 2 晚

    @Autowired
    private ReservationExpiryService expiryService;
    @Autowired
    private ReservationCommandService reservationCommandService;
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private RoomService roomService;
    @Autowired
    private ReservationMapper reservationMapper;
    @Autowired
    private ReservationExpiryMapper expiryMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    // ===================== 验收点 4：到期 != 已释放 =====================

    @Test
    void overdueHoldStillBlocksRoomUntilStatusMigrates() {
        Fixture f = fixture("e1", 1);
        ReservationView created = create(f, "create-1", StayRange.of(IN, OUT));
        UUID roomId = created.lines().get(0).assignedRoomId();

        overdue(created.id());

        // 时间已过截止点，但状态仍是 HELD → 仍然占房（不能只凭 expires_at 就算房间可售）
        assertEquals("HELD", occupancyStatusOf(created.id()));
        assertTrue(availableRoomIds(f).stream().noneMatch(id -> id.equals(roomId)),
                "仅时间到期、尚未迁移状态的占用必须仍计有效占用");

        ExpiryReport report = expiryService.expireDueHolds(10);
        assertEquals(1, report.scanned());
        assertEquals(1, report.expired());
        assertEquals(0, report.skipped());

        // 迁移之后：订单/行/占用三方一致，房间回到可售
        assertEquals("EXPIRED", statusOf(created.id()));
        assertEquals("EXPIRED", lineStatusOf(created.id()));
        assertEquals("EXPIRED", occupancyStatusOf(created.id()));
        assertTrue(availableRoomIds(f).contains(roomId), "释放后该房间应重新可售");

        // 释放是对冲行（+1），不是删掉占用历史：两晚各一条，占用（-1）仍在
        assertEquals(2, count("inventory_ledger WHERE room_id = ? AND delta = 1", roomId));
        assertEquals(2, count("inventory_ledger WHERE room_id = ? AND delta = -1", roomId));
        assertEquals(0, sumDeltaOf(roomId, IN));
        // 审计如实记为 SYSTEM：Worker 没有可归属的登录人，不伪造用户
        assertEquals(1, count("audit_log WHERE action = 'reservation.expire' AND actor_type = 'SYSTEM'"));
    }

    @Test
    void notYetDueHoldIsLeftAlone() {
        Fixture f = fixture("e2", 1);
        ReservationView created = create(f, "create-1", StayRange.of(IN, OUT));

        ExpiryReport report = expiryService.expireDueHolds(10);
        assertEquals(0, report.scanned());
        assertEquals("PENDING", statusOf(created.id()));
        assertEquals("HELD", occupancyStatusOf(created.id()));
        assertEquals(0, count("audit_log WHERE action = 'reservation.expire'"));
    }

    @Test
    void multiRoomReservationExpiresOnlyWhenEveryHoldIsOverdue() {
        Fixture f = fixture("e3", 2);
        ReservationView created = create(f, "create-1", StayRange.of(IN, OUT), StayRange.of(IN, OUT));
        assertEquals(2, created.lines().size());

        // 只让其中一条 HELD 到期
        UUID firstLineId = created.lines().get(0).lineId();
        jdbcTemplate.update("UPDATE room_occupancy SET expires_at = now() - interval '5 minutes' "
                + "WHERE reservation_id = ? AND line_id = ?", created.id(), firstLineId);

        // 部分到期不得整单释放：否则另一个房间会被一起放出去
        assertEquals(0, expiryService.expireDueHolds(10).expired());
        assertEquals("PENDING", statusOf(created.id()));
        assertEquals(2, count("room_occupancy WHERE reservation_id = ? AND status = 'HELD'", created.id()));

        // 全部到期后才整单迁移
        overdue(created.id());
        assertEquals(1, expiryService.expireDueHolds(10).expired());
        assertEquals("EXPIRED", statusOf(created.id()));
        assertEquals(2, count("room_occupancy WHERE reservation_id = ? AND status = 'EXPIRED'", created.id()));
    }

    // ===================== 验收点 1：两个 Worker 只释放一次 =====================

    @Test
    void twoWorkersScanningSameBatchReleaseExactlyOnce() throws Exception {
        Fixture f = fixture("w1", 1);
        ReservationView created = create(f, "create-1", StayRange.of(IN, OUT));
        UUID roomId = created.lines().get(0).assignedRoomId();
        overdue(created.id());

        AtomicInteger released = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(2);
        runConcurrently(
                () -> { awaitStart(start); released.addAndGet(expiryService.expireDueHolds(10).expired()); },
                () -> { awaitStart(start); released.addAndGet(expiryService.expireDueHolds(10).expired()); });

        assertEquals(1, released.get(), "两个 Worker 合计只应释放一次，实际 " + released.get());
        assertEquals(1, count("room_occupancy WHERE reservation_id = ?", created.id()));
        assertEquals("EXPIRED", occupancyStatusOf(created.id()));
        // 释放流水只有一组（两晚），重复释放会再多一组 +1
        assertEquals(2, count("inventory_ledger WHERE room_id = ? AND delta = 1", roomId));
        assertEquals(1, count("audit_log WHERE action = 'reservation.expire'"));
    }

    /**
     * SKIP LOCKED 的精确验证——上面的双 Worker 用例只能证明"合计只释放一次"，
     * 串行执行也能通过。这里让 Worker A <b>领取后停在事务里不提交</b>，
     * 此时 Worker B 必须**立刻跳过**这些行（scanned = 0）；
     * 若实现里少了 SKIP LOCKED，B 会阻塞到 A 结束 —— 而 A 正在等 B，用例会超时失败。
     * 之后 A 回滚（模拟崩溃），条件仍成立，下一轮可正常领取并释放。
     */
    @Test
    void workerSkipsRowsAlreadyClaimedByAnotherWorker() throws Exception {
        Fixture f = fixture("k1", 1);
        ReservationView created = create(f, "create-1", StayRange.of(IN, OUT));
        overdue(created.id());

        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch scanned = new CountDownLatch(1);
        AtomicInteger secondScanned = new AtomicInteger(-1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(tx -> {
                        assertEquals(1, expiryMapper.claimDueReservations(10).size(), "A 应领到这单");
                        claimed.countDown();
                        await(scanned);      // 等 B 扫描结束，期间一直持锁
                        tx.setRollbackOnly(); // 模拟 A 处理到一半崩溃
                    }));
            Future<?> scanner = pool.submit(() -> {
                await(claimed);
                secondScanned.set(expiryService.expireDueHolds(10).scanned());
                scanned.countDown();
            });
            holder.get(30, TimeUnit.SECONDS);
            scanner.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, secondScanned.get(), "已被其他 Worker 领取的行必须被跳过，而不是阻塞或重复领取");
        assertEquals("PENDING", statusOf(created.id()), "A 未提交，订单不应有变化");

        // A 崩溃后没有任何半完成状态；到期条件仍成立 → 下一轮正常释放
        assertEquals(1, expiryService.expireDueHolds(10).expired());
        assertEquals("EXPIRED", statusOf(created.id()));
    }

    // ===================== 验收点 2：确认与到期竞争 =====================

    @Test
    void concurrentConfirmAndExpiryKeepJointStateConsistent() throws Exception {
        Fixture f = fixture("c1", 1);
        ReservationView created = create(f, "create-1", StayRange.of(IN, OUT));
        overdue(created.id());

        runConcurrently(
                () -> confirm(f, created.id(), "confirm-1"),
                () -> expiryService.expireDueHolds(10));

        String resStatus = statusOf(created.id());
        String occStatus = occupancyStatusOf(created.id());
        assertTrue(Set.of("CONFIRMED", "EXPIRED").contains(resStatus),
                "最终只能是已确认或已到期，实际 " + resStatus);
        // 联合状态必须一致：不存在"订单已确认但占用被释放"这种既卖又不卖的中间态
        assertEquals(resStatus, occStatus, "订单状态与占用状态必须一致");
        assertEquals(1, count("room_occupancy WHERE reservation_id = ?", created.id()));
        assertEquals(1, count("reservation_line WHERE reservation_id = ?", created.id()));
    }

    @Test
    void confirmedReservationIsNotTouchedByExpiryScan() {
        Fixture f = fixture("c2", 1);
        ReservationView created = create(f, "create-1", StayRange.of(IN, OUT));
        confirm(f, created.id(), "confirm-1");
        // 已确认单的占用已是 CONFIRMED：即使时间过点也不归到期任务管
        overdue(created.id());

        assertEquals(0, expiryService.expireDueHolds(10).scanned());
        assertEquals("CONFIRMED", statusOf(created.id()));
        assertEquals("CONFIRMED", occupancyStatusOf(created.id()));
    }

    @Test
    void cancelledReservationIsNotExpiredAgain() {
        Fixture f = fixture("c3", 1);
        ReservationView created = create(f, "create-1", StayRange.of(IN, OUT));
        reservationCommandService.cancelReservation(f.tenantId(), created.id(),
                new ReservationCommandService.LifecycleCommand(f.hotelId(), "test-client", "cancel-1", null));
        overdue(created.id());

        assertEquals(0, expiryService.expireDueHolds(10).scanned());
        assertEquals("CANCELLED", statusOf(created.id()));
        assertEquals("RELEASED", occupancyStatusOf(created.id()));
    }

    // ===================== 验收点 3：中断无半完成 + 可重新领取 =====================

    @Test
    void interruptedBatchLeavesNoHalfStateAndNextRoundRecovers() {
        Fixture f = fixture("i1", 1);
        ReservationView created = create(f, "create-1", StayRange.of(IN, OUT));
        UUID roomId = created.lines().get(0).assignedRoomId();
        overdue(created.id());

        // 模拟"领取并处理到一半，进程在提交前挂掉"：外层事务回滚，等同于崩溃后的库内状态
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertEquals(1, expiryService.expireDueHolds(10).expired());
            status.setRollbackOnly();
        });

        assertEquals("PENDING", statusOf(created.id()), "中断后订单必须仍是 PENDING");
        assertEquals("HELD", occupancyStatusOf(created.id()), "中断后占用必须仍是 HELD");
        assertEquals(0, count("inventory_ledger WHERE room_id = ? AND delta = 1", roomId));
        assertEquals(0, count("audit_log WHERE action = 'reservation.expire'"));
        assertEquals(0, count("idempotency_record WHERE command_name = 'reservation.expire'"));

        // 到期条件仍然成立 → 下一轮能重新领取并完成释放（这就是"崩溃后重新领取"）
        assertEquals(1, expiryService.expireDueHolds(10).expired());
        assertEquals("EXPIRED", statusOf(created.id()));
        assertEquals("EXPIRED", occupancyStatusOf(created.id()));
    }

    @Test
    void secondRoundWithNoDueWorkDoesNothing() {
        Fixture f = fixture("n1", 1);
        ReservationView created = create(f, "create-1", StayRange.of(IN, OUT));
        overdue(created.id());

        assertEquals(1, expiryService.expireDueHolds(10).expired());
        assertEquals(0, expiryService.expireDueHolds(10).scanned());
        // 已 EXPIRED 的单不会被二次处理：占用仍是一行，审计仍是一条
        assertEquals(1, count("room_occupancy WHERE reservation_id = ?", created.id()));
        assertEquals(1, count("audit_log WHERE action = 'reservation.expire'"));
    }

    // ===================== 批次限制 =====================

    @Test
    void batchSizeCapsWorkPerRound() {
        Fixture f = fixture("b1", 3);
        for (int i = 1; i <= 3; i++) {
            ReservationView created = create(f, "create-" + i, StayRange.of(IN, OUT));
            overdue(created.id());
        }

        assertEquals(2, expiryService.expireDueHolds(2).expired());
        assertEquals(2, count("reservation WHERE status = 'EXPIRED'"));
        assertEquals(1, expiryService.expireDueHolds(2).expired());
        assertEquals(3, count("reservation WHERE status = 'EXPIRED'"));
        assertEquals(0, expiryService.expireDueHolds(2).scanned());
    }

    // ===================== 辅助 =====================

    private void runConcurrently(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(wrap(first));
            Future<?> b = pool.submit(wrap(second));
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 让两个线程尽量同时起跑：否则"并发"用例经常退化成先后执行，什么也证明不了。 */
    private void awaitStart(CyclicBarrier barrier) {
        try {
            barrier.await(20, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("并发起跑协调失败", ex);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS), "并发协调超时");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发协调被中断", ex);
        }
    }

    /** 并发用例允许一方被状态机拒绝（409），那是合法结果而不是失败；其他异常原样抛出。 */
    private Runnable wrap(Runnable work) {
        return () -> {
            try {
                work.run();
            } catch (ConflictException ex) {
                // 合法：确认撞上已到期，或到期撞上已确认
            }
        };
    }

    private ReservationView create(Fixture f, String key, StayRange... ranges) {
        List<ReservationCommandService.CreateCommand.LineCommand> lines = java.util.Arrays.stream(ranges)
                .map(range -> new ReservationCommandService.CreateCommand.LineCommand(f.typeA(), range))
                .toList();
        return reservationCommandService.createReservation(f.tenantId(),
                new ReservationCommandService.CreateCommand(f.hotelId(), "test-client",
                        key + "-" + f.suffix(), ReservationSourceType.FRONTDESK, lines));
    }

    private ReservationView confirm(Fixture f, UUID id, String key) {
        return reservationCommandService.confirmReservation(f.tenantId(), id,
                new ReservationCommandService.LifecycleCommand(f.hotelId(), "test-client", key, null));
    }

    /** 把该订单所有 HELD 占用的截止点推到过去（用数据库时钟，与生产判定同源）。 */
    private void overdue(UUID reservationId) {
        jdbcTemplate.update("UPDATE room_occupancy SET expires_at = now() - interval '5 minutes' "
                + "WHERE reservation_id = ? AND status = 'HELD'", reservationId);
    }

    private List<UUID> availableRoomIds(Fixture f) {
        return reservationMapper.findAvailableRooms(f.tenantId(), f.hotelId(), f.typeA(),
                StayRange.of(IN, OUT)).stream().map(PhysicalRoom::id).toList();
    }

    private String statusOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id);
    }

    private String lineStatusOf(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM reservation_line WHERE reservation_id = ?", String.class, id);
    }

    private String occupancyStatusOf(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM room_occupancy WHERE reservation_id = ?", String.class, id);
    }

    private int sumDeltaOf(UUID roomId, LocalDate stayDate) {
        Integer sum = jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(delta), 0) FROM inventory_ledger WHERE room_id = ? AND stay_date = ?",
                Integer.class, roomId, stayDate);
        return sum == null ? 0 : sum;
    }

    private int count(String where, Object... args) {
        Integer value = jdbcTemplate.queryForObject("SELECT count(*) FROM " + where, Integer.class, args);
        return value == null ? 0 : value;
    }

    private Fixture fixture(String suffix, int roomsOfTypeA) {
        Tenant tenant = organizationService.createTenant("t-" + suffix, "到期承接测试租户 " + suffix);
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
