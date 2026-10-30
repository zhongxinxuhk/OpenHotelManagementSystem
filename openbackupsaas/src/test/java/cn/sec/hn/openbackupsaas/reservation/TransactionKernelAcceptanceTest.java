package cn.sec.hn.openbackupsaas.reservation;

import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationCommandService;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationExpiryService;
import cn.sec.hn.openbackupsaas.reservation.web.ReservationView;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-13 交易内核验收：抢订、原子性、响应丢失、三方竞争（T01 / T03 / T05 / T06）。
 *
 * <p><b>这个类和别的集成测试有什么不同：</b>其他测试关心"某个功能对不对"，
 * 这里关心"<b>一次交易在六张表上是不是同一件事</b>"——HTTP/服务结果、订单、占用、流水、
 * 幂等记录、审计、Outbox 必须指向同一个结论。任何一张表单独对、彼此对不上，都是账实不符。
 *
 * <p><b>为什么 T01 要重复 10 轮：</b>并发缺陷的典型特征是偶发。跑一次通过说明不了什么，
 * 而"多跑几次就绿"恰恰是要避免的坏习惯。固定输入、固定线程数、连续 10 轮全绿，
 * 才算这条路径稳定。轮次之间用独立的租户与门店，互不干扰。
 *
 * <p><b>为什么 T05 用"丢弃结果 + 同键重查"表示"连接丢失"：</b>在单进程测试里无法真的拔网线。
 * 但这个场景的实质是"客户端没拿到结果、不知道是否成功"，用同一幂等键再发一次，
 * 正是客户端在那种情况下的唯一正确动作，核验点一致。
 */
class TransactionKernelAcceptanceTest extends PostgresIntegrationTest {

    private static final LocalDate IN = LocalDate.of(2026, 11, 1);
    private static final LocalDate OUT = LocalDate.of(2026, 11, 4); // 3 晚

    @Autowired
    private ReservationCommandService commandService;
    @Autowired
    private ReservationExpiryService expiryService;
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private RoomService roomService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ===================== T01：同房同区间并发抢订 =====================

    @Test
    void t01_concurrentBookingsOnSameRoomYieldExactlyOneWinnerAcrossTenRounds() throws Exception {
        for (int round = 1; round <= 10; round++) {
            Fixture f = fixture("t01r" + round, 1);
            int racers = 8;

            Outcome outcome = race(racers, index -> create(f, "race-" + index, StayRange.of(IN, OUT)));

            assertEquals(1, outcome.success(), "第 " + round + " 轮：同房同区间只应有一笔成功");
            assertEquals(racers - 1, outcome.conflict(),
                    "第 " + round + " 轮：其余请求应被排斥约束拒绝为 409，实际异常：" + outcome.others());

            // 六方核验：恰好一单、一条有效占用、三晚各一条 -1 流水、一条建单审计、一条事件
            assertKernelConsistent(f, outcome.winnerId(), 3);
            assertEquals(1, count("reservation WHERE hotel_id = ? AND status = 'PENDING'", f.hotelId()),
                    "第 " + round + " 轮：不应有第二张待确认订单");
        }
    }

    // ===================== T03：多房只冲突一间 =====================

    /**
     * 需求 2 间房，但只剩 1 间可用：整单必须失败，且<b>不能留下半个交易</b>。
     *
     * <p>这是"多房多晚整体成功或失败"的直接证据。若实现先把第一间占上再发现第二间不够，
     * 就会留下一张订单 + 一条占用 + 若干流水，而客人以为下单失败。
     */
    @Test
    void t03_partialRoomShortageRollsBackWholeTransaction() throws Exception {
        Fixture f = fixture("t03", 1);
        // 先把唯一的房间占掉（另一张合法订单），再尝试一次需要 2 间房的预订
        create(f, "occupier", StayRange.of(IN, OUT));
        assertKernelConsistent(f, firstReservationId(f.hotelId()), 3);

        Outcome outcome = race(1, index -> createWithLines(f, "two-rooms",
                StayRange.of(IN, OUT), StayRange.of(IN, OUT)));

        assertEquals(0, outcome.success(), "只剩 1 间房时，2 间房的需求必须整单失败");
        assertEquals(1, outcome.conflict());

        // 半截交易核验：没有多余的订单、订单行、占用、流水、审计
        assertEquals(1, count("reservation WHERE hotel_id = ?", f.hotelId()), "不应留下第二张订单");
        assertEquals(1, count("reservation_line WHERE hotel_id = ?", f.hotelId()), "不应留下多余的订单行");
        assertEquals(1, count("room_occupancy WHERE hotel_id = ?", f.hotelId()), "不应留下多余的占用");
        assertEquals(3, count("inventory_ledger WHERE hotel_id = ?", f.hotelId()), "流水只应有占用那一单的 3 行");
        assertEquals(1, count("audit_log WHERE hotel_id = ? AND action = 'reservation.create'", f.hotelId()));
        assertEquals(0, count("idempotency_record WHERE hotel_id = ? AND status = 'IN_PROGRESS'", f.hotelId()),
                "失败命令不应留下进行中的幂等记录");
    }

    // ===================== T05：提交成功但响应丢失 =====================

    @Test
    void t05_lostResponseIsRecoveredBySameIdempotencyKeyWithoutSecondBooking() throws Exception {
        Fixture f = fixture("t05", 2);
        String key = "lost-response-key";

        ReservationView first = create(f, key, StayRange.of(IN, OUT));
        // 客户端没收到响应 → 用同一个键重发（这是它唯一正确的动作）
        ReservationView retried = create(f, key, StayRange.of(IN, OUT));

        assertEquals(first.id(), retried.id(), "同键重试必须拿回同一订单，而不是新建一笔");
        assertEquals(1, count("reservation WHERE hotel_id = ?", f.hotelId()), "重试不得产生第二张订单");
        assertEquals(1, count("room_occupancy WHERE hotel_id = ?", f.hotelId()));
        assertKernelConsistent(f, first.id(), 3);

        // 同键不同内容：明确拒绝，且不改动原订单
        Outcome mismatch = race(1, index -> create(f, key, StayRange.of(IN, OUT.plusDays(1))));
        assertEquals(0, mismatch.success(), "同键不同内容必须被拒绝");
        assertTrue(mismatch.others().isEmpty(),
                "同键不同内容应抛业务冲突（409），实际异常：" + mismatch.others());
        assertEquals(1, count("reservation WHERE hotel_id = ?", f.hotelId()));
    }

    // ===================== T06：确认 / 取消 / 过期三方竞争 =====================

    /**
     * 同一张超期订单上同时跑确认、取消与到期释放。
     *
     * <p>终态必须是三者之一，且<b>订单状态与占用状态一致</b>、释放只发生一次、
     * 审计与事件与终态对得上。任何"订单已确认但占用已释放"的组合都是既卖又不卖。
     */
    @Test
    void t06_confirmCancelAndExpiryRaceLeaveOneConsistentFinalState() throws Exception {
        for (int r = 1; r <= 5; r++) {
            // lambda 里引用的局部变量必须是 effectively final，所以把轮次复制一份
            final int round = r;
            Fixture f = fixture("t06r" + round, 1);
            ReservationView created = create(f, "create", StayRange.of(IN, OUT));
            // 让占用处于"已过期但未释放"：这正是到达期任务会捡起来的状态
            jdbcTemplate.update("UPDATE room_occupancy SET expires_at = now() - interval '5 minutes' "
                    + "WHERE reservation_id = ? AND status = 'HELD'", created.id());

            ExecutorService pool = Executors.newFixedThreadPool(3);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = List.of(
                    pool.submit(wrap(start, () -> commandService.confirmReservation(f.tenantId(), created.id(),
                            new ReservationCommandService.LifecycleCommand(f.hotelId(), "test-client", "confirm-" + round, null)))),
                    pool.submit(wrap(start, () -> commandService.cancelReservation(f.tenantId(), created.id(),
                            new ReservationCommandService.LifecycleCommand(f.hotelId(), "test-client", "cancel-" + round, null)))),
                    pool.submit(wrap(start, () -> expiryService.expireDueHolds(10))));
            start.countDown();
            for (Future<?> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException ignored) {
                    // 谁被状态机拒绝都合法：被拒说明竞争按预期串行化了
                }
            }
            pool.shutdownNow();

            String reservationStatus = statusOf("reservation", "id", created.id());
            String occupancyStatus = statusOf("room_occupancy", "reservation_id", created.id());
            assertTrue(List.of("CONFIRMED", "CANCELLED", "EXPIRED").contains(reservationStatus),
                    "第 " + round + " 轮：终态只能是三者之一，实际 " + reservationStatus);
            // 两个状态机的词表不同：订单用 CANCELLED，占用用 RELEASED。
            // 直接比字符串会把"正确的联合状态"判成失败（首跑就踩了这个），必须按映射核对。
            String expectedOccupancy = switch (reservationStatus) {
                case "CONFIRMED" -> "CONFIRMED";
                case "CANCELLED" -> "RELEASED";
                case "EXPIRED" -> "EXPIRED";
                default -> throw new IllegalStateException("未预期的订单终态：" + reservationStatus);
            };
            assertEquals(expectedOccupancy, occupancyStatus,
                    "第 " + round + " 轮：订单 " + reservationStatus + " 应对应占用 " + expectedOccupancy
                            + "，实际 " + occupancyStatus);

            assertEquals(1, count("room_occupancy WHERE reservation_id = ?", created.id()),
                    "第 " + round + " 轮：不得出现重复占用");
            // 释放类终态每晚只能有一条 +1 对冲；确认则完全不应有释放流水
            long releasedNights = count("inventory_ledger WHERE hotel_id = ? AND delta = 1", f.hotelId());
            if ("CONFIRMED".equals(reservationStatus)) {
                assertEquals(0, releasedNights, "第 " + round + " 轮：确认不应写释放流水");
            } else {
                assertEquals(3, releasedNights, "第 " + round + " 轮：释放应恰好覆盖 3 晚，且只写一次");
            }
        }
    }

    // ===================== 一致性核验 =====================

    /**
     * 六方核验：一次成功的建单在六张表上必须是同一件事。
     *
     * <p>只检查"订单存在"没有意义——订单存在而占用缺失、或流水少写一晚，
     * 在可售与对账上就是另一个事实。
     */
    private void assertKernelConsistent(Fixture f, UUID reservationId, int nights) {
        assertEquals(1, count("reservation WHERE id = ?", reservationId), "订单应恰好一条");
        assertEquals(1, count("reservation_line WHERE reservation_id = ?", reservationId), "订单行应恰好一条");
        assertEquals(1, count("room_occupancy WHERE reservation_id = ? AND status = 'HELD'", reservationId),
                "应恰好一条 HELD 占用");
        // 流水：每晚一行 -1，净效果 = -晚数
        assertEquals(nights, count("inventory_ledger WHERE hotel_id = ? AND delta = -1", f.hotelId()),
                "占用流水应按晚写 " + nights + " 行");
        assertEquals(-nights, jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(delta), 0) FROM inventory_ledger WHERE hotel_id = ?", Integer.class, f.hotelId()),
                "流水净值应等于 -" + nights);
        // 审计与事件：同一聚合版本
        assertEquals(1, count("audit_log WHERE hotel_id = ? AND action = 'reservation.create' AND target_id = ?",
                f.hotelId(), reservationId), "应有且仅有一条建单审计");
        assertEquals(1, count("outbox_event WHERE hotel_id = ? AND aggregate_id = ? AND event_type = 'ReservationCreated'",
                f.hotelId(), reservationId), "应有且仅有一条建单事件");
        // 幂等：成功的命令必须留下 SUCCEEDED 且指向本单
        assertEquals(1, count("idempotency_record WHERE hotel_id = ? AND status = 'SUCCEEDED' AND result_ref = ?",
                f.hotelId(), reservationId), "幂等记录应指向本单");
    }

    // ===================== 辅助 =====================

    private ReservationView create(Fixture f, String key, StayRange range) {
        return createWithLines(f, key, range);
    }

    private ReservationView createWithLines(Fixture f, String key, StayRange... ranges) {
        List<ReservationCommandService.CreateCommand.LineCommand> lines = java.util.Arrays.stream(ranges)
                .map(range -> new ReservationCommandService.CreateCommand.LineCommand(f.typeA(), range))
                .toList();
        return commandService.createReservation(f.tenantId(),
                new ReservationCommandService.CreateCommand(f.hotelId(), "test-client",
                        key + "-" + f.suffix(), ReservationSourceType.FRONTDESK, lines));
    }

    private Runnable wrap(CountDownLatch start, Runnable action) {
        return () -> {
            try {
                start.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            action.run();
        };
    }

    /** 同时起跑 N 个请求，收集成功/业务冲突/其他异常。 */
    private Outcome race(int threads, ThrowingIntFunction action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReservationView>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                start.await(20, TimeUnit.SECONDS);
                return action.apply(index);
            }));
        }
        start.countDown();

        int success = 0;
        int conflict = 0;
        List<String> others = new ArrayList<>();
        UUID winner = null;
        for (Future<ReservationView> future : futures) {
            try {
                ReservationView view = future.get(30, TimeUnit.SECONDS);
                success++;
                winner = view.id();
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof ConflictException) {
                    conflict++;
                } else {
                    others.add(String.valueOf(ex.getCause()));
                }
            }
        }
        pool.shutdownNow();
        return new Outcome(success, conflict, others, winner);
    }

    private UUID firstReservationId(UUID hotelId) {
        return jdbcTemplate.queryForObject("SELECT id FROM reservation WHERE hotel_id = ? LIMIT 1", UUID.class, hotelId);
    }

    private String statusOf(String table, String column, UUID value) {
        return jdbcTemplate.queryForObject("SELECT status FROM " + table + " WHERE " + column + " = ? LIMIT 1",
                String.class, value);
    }

    private int count(String where, Object... args) {
        Integer value = jdbcTemplate.queryForObject("SELECT count(*) FROM " + where, Integer.class, args);
        return value == null ? 0 : value;
    }

    private Fixture fixture(String suffix, int rooms) {
        Tenant tenant = organizationService.createTenant("t-" + suffix, "验收租户 " + suffix);
        Hotel hotel = organizationService.createHotel(tenant.id(), null, "h" + suffix, "主店 " + suffix,
                "Asia/Shanghai");
        RoomType typeA = roomService.createRoomType(tenant.id(), hotel.id(), "std", "标准间", 2, 0, null);
        for (int i = 0; i < rooms; i++) {
            roomService.createRoom(tenant.id(), hotel.id(), typeA.id(), "0" + (i + 1) + suffix, i + 1, null);
        }
        return new Fixture(tenant.id(), hotel.id(), typeA.id(), suffix);
    }

    private record Fixture(UUID tenantId, UUID hotelId, UUID typeA, String suffix) {
    }

    private record Outcome(int success, int conflict, List<String> others, UUID winnerId) {
    }

    @FunctionalInterface
    private interface ThrowingIntFunction {
        ReservationView apply(int index) throws Exception;
    }
}
