package cn.sec.hn.openbackupsaas.inbox;

import cn.sec.hn.openbackupsaas.inbox.domain.InboxMessage;
import cn.sec.hn.openbackupsaas.inbox.domain.InboxOutcome;
import cn.sec.hn.openbackupsaas.inbox.service.InboxConsumer;
import cn.sec.hn.openbackupsaas.inbox.service.InboxIntegrityService;
import cn.sec.hn.openbackupsaas.inbox.service.InboxProcessor;
import cn.sec.hn.openbackupsaas.inbox.service.RoomProjectionConsumer;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationCommandService;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.shared.security.TenantContext;
import cn.sec.hn.openbackupsaas.shared.security.TenantContextHolder;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inbox 消费与版本保护集成测试（S2-02），**必须连真实 PostgreSQL**。
 *
 * <p>覆盖任务里写明的四条验收：
 * <ol>
 *   <li>重复消息只有一次业务效果；</li>
 *   <li>失败事务不遗留已处理标记；</li>
 *   <li>旧事件不覆盖新状态；</li>
 *   <li>版本缺口可补查或挂起，不静默丢掉必要的状态迁移。</li>
 * </ol>
 *
 * <p>前几例用计数的测试消费者（直接数"业务被应用了几次"），最后几例用真实消费者
 * {@link RoomProjectionConsumer} 跑真实订单事件——模板对了但真实消费者跑不通，等于什么都没交付。
 */
class InboxIntegrationTest extends PostgresIntegrationTest {

    private static final String CONSUMER = "test-counting";
    private static final LocalDate D1 = LocalDate.of(2026, 11, 10);

    /**
     * 同一个用例内的消息必须共用聚合 id，否则每条都会被判成"首次见到该聚合"而全部放行。
     * JUnit 每个用例新建一个实例，因此字段级初始化正好等于"每个用例一个聚合"。
     */
    private final UUID aggregateId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID hotelId = UUID.randomUUID();

    @Autowired
    private InboxProcessor processor;
    @Autowired
    private InboxIntegrityService integrityService;
    @Autowired
    private RoomProjectionConsumer roomProjectionConsumer;
    @Autowired
    private CountingConsumer countingConsumer;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private RoomService roomService;
    @Autowired
    private ReservationCommandService reservationCommandService;

    @TestConfiguration(proxyBeanMethods = false)
    static class CountingConsumerConfig {
        @Bean
        CountingConsumer countingConsumer() {
            return new CountingConsumer();
        }
    }

    /**
     * 计数消费者：把"业务效果"变成可数的次数。
     *
     * <p>不用"写一张表再数行数"的方式：那要求测试自己建表，而测试建的表既不在迁移里、
     * 也不会被清理机制理解。在内存里数调用次数，直接把"应用了几次"暴露成断言对象。
     */
    static class CountingConsumer extends InboxConsumer {

        final List<InboxMessage> applied = Collections.synchronizedList(new ArrayList<>());
        volatile boolean failNextApply;

        @Override
        public String name() {
            return CONSUMER;
        }

        @Override
        public Set<String> handledEventTypes() {
            return Set.of("ReservationCreated", "ReservationConfirmed");
        }

        @Override
        public void apply(InboxMessage message) {
            if (failNextApply) {
                failNextApply = false;
                throw new IllegalStateException("模拟业务失败");
            }
            applied.add(message);
        }

        void reset() {
            applied.clear();
            failNextApply = false;
        }
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        countingConsumer.reset();
    }

    // ------------------------------------------------------------------
    // 1. 重复消息只有一次业务效果
    // ------------------------------------------------------------------

    @Test
    void duplicateDeliveryAppliesBusinessEffectOnce() {
        InboxMessage message = message("ReservationCreated", 1, 1);

        InboxProcessor.InboxResult first = processor.process(countingConsumer, message);
        InboxProcessor.InboxResult second = processor.process(countingConsumer, message);

        assertEquals(InboxOutcome.PROCESSED, first.outcome());
        assertFalse(first.duplicate());
        assertTrue(second.duplicate(), "第二次必须是'重复'而不是又应用一次");
        assertEquals(InboxOutcome.PROCESSED, second.outcome(), "重复时应回报第一次的结论，便于排查");
        assertEquals(1, countingConsumer.applied.size(), "业务效果只应发生一次，实际 "
                + countingConsumer.applied.size());
        assertEquals(1, count("inbox_event WHERE consumer_name = ? AND event_id = ?",
                CONSUMER, message.eventId()), "台账只应有一行");
    }

    @Test
    void concurrentDuplicateDeliveryAppliesOnce() throws Exception {
        InboxMessage message = message("ReservationCreated", 1, 1);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<InboxProcessor.InboxResult> a = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return processor.process(countingConsumer, message);
            });
            Future<InboxProcessor.InboxResult> b = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return processor.process(countingConsumer, message);
            });
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, countingConsumer.applied.size(),
                "并发投递同一事件时业务只能应用一次（唯一键 + ON CONFLICT 的意义），实际 "
                        + countingConsumer.applied.size());
        assertEquals(1, count("inbox_event WHERE event_id = ?", message.eventId()));
    }

    // ------------------------------------------------------------------
    // 2. 失败事务不遗留已处理标记
    // ------------------------------------------------------------------

    @Test
    void failedApplyLeavesNoInboxMarkAndCanBeRetried() {
        InboxMessage message = message("ReservationCreated", 1, 1);
        countingConsumer.failNextApply = true;

        assertThrows(IllegalStateException.class, () -> processor.process(countingConsumer, message),
                "业务失败必须抛出，让容器重投；吞掉异常等于把消息标记为已处理");

        assertEquals(0, count("inbox_event WHERE event_id = ?", message.eventId()),
                "失败时台账必须随事务回滚——留下'处理过'的标记会让这条消息永久丢失");
        assertEquals(0, count("inbox_consumer_position WHERE consumer_name = ?", CONSUMER),
                "失败时位点不能推进");

        InboxProcessor.InboxResult retry = processor.process(countingConsumer, message);
        assertEquals(InboxOutcome.PROCESSED, retry.outcome());
        assertEquals(1, countingConsumer.applied.size(), "重试应正常应用一次");
    }

    // ------------------------------------------------------------------
    // 3. 旧事件不覆盖新状态
    // ------------------------------------------------------------------

    @Test
    void staleEventIsSkippedInsteadOfApplied() {
        InboxMessage confirmed = message("ReservationConfirmed", 5, 1);
        processor.process(countingConsumer, confirmed);
        assertEquals(1, countingConsumer.applied.size());

        InboxMessage created = message("ReservationCreated", 1, 1);
        InboxProcessor.InboxResult stale = processor.process(countingConsumer, created);

        assertEquals(InboxOutcome.SKIPPED_STALE, stale.outcome(),
                "位点已到 v5，v1 属旧事件，应用它会把新状态覆盖回旧状态");
        assertEquals(1, countingConsumer.applied.size(), "旧事件不得产生任何业务效果");
        assertEquals(1, count("inbox_event WHERE event_id = ? AND outcome = 'SKIPPED_STALE'",
                created.eventId()), "跳过也要留痕，否则无法区分'没收到'与'收到了但过期'");
        assertEquals(1, count("inbox_consumer_position WHERE consumer_name = ? AND last_version = 5",
                CONSUMER), "位点不得倒退");
    }

    @Test
    void sameVersionSecondOrdinalIsNotTreatedAsDuplicate() {
        InboxMessage first = message("ReservationCreated", 3, 1);
        InboxMessage second = message("ReservationConfirmed", 3, 2);

        processor.process(countingConsumer, first);
        InboxProcessor.InboxResult result = processor.process(countingConsumer, second);

        assertEquals(InboxOutcome.PROCESSED, result.outcome(),
                "同版本的第二条事件是新事件，只按版本判重会把它误判成重复");
        assertEquals(2, countingConsumer.applied.size());
        assertEquals(1, count("inbox_consumer_position WHERE consumer_name = ? "
                + "AND last_version = 3 AND last_ordinal = 2", CONSUMER));
    }

    // ------------------------------------------------------------------
    // 4. 版本缺口：挂起 + 补齐后重放
    // ------------------------------------------------------------------

    @Test
    void gapIsParkedThenRedrivenAfterMissingVersionArrives() {
        InboxMessage v1 = message("ReservationCreated", 1, 1);
        InboxMessage v3 = message("ReservationConfirmed", 3, 1);
        processor.process(countingConsumer, v1);

        InboxProcessor.InboxResult parked = processor.process(countingConsumer, v3);

        assertEquals(InboxOutcome.PARKED_GAP, parked.outcome(),
                "v1 之后直接收到 v3，中间那版丢了，不能猜着应用");
        assertEquals(1, countingConsumer.applied.size(), "缺口事件不得产生业务效果");
        assertEquals(1, integrityService.parkedCount(CONSUMER));
        assertEquals(1, count("inbox_event WHERE event_id = ? AND settled_at IS NULL", v3.eventId()),
                "挂起必须没有结算时间——它要能被重放");
        assertEquals(1, count("inbox_consumer_position WHERE consumer_name = ? AND last_version = 1",
                CONSUMER), "挂起时位点必须停在缺口前");

        // 缺口补齐：v2 到达后正常应用
        InboxMessage v2 = message("ReservationConfirmed", 2, 1);
        assertEquals(InboxOutcome.PROCESSED, processor.process(countingConsumer, v2).outcome());

        // 重放 v3：位点已是 v2，现在可以应用
        InboxIntegrityService.RedriveReport report = integrityService.redriveParked(CONSUMER, 10);

        assertEquals(1, report.candidates());
        assertEquals(1, report.processed(), "缺口补齐后重放应成功应用");
        assertEquals(0, report.stillParked());
        assertEquals(0, integrityService.parkedCount(CONSUMER), "重放后不该还挂着");
        assertEquals(3, countingConsumer.applied.size(), "v1、v2、v3 各应用一次");
        assertEquals(1, count("inbox_event WHERE event_id = ? AND outcome = 'PROCESSED'", v3.eventId()));
        assertEquals(1, count("inbox_consumer_position WHERE consumer_name = ? AND last_version = 3",
                CONSUMER));
    }

    @Test
    void redriveWithoutMissingVersionKeepsEventParked() {
        InboxMessage v1 = message("ReservationCreated", 1, 1);
        InboxMessage v4 = message("ReservationConfirmed", 4, 1);
        processor.process(countingConsumer, v1);
        processor.process(countingConsumer, v4);

        InboxIntegrityService.RedriveReport report = integrityService.redriveParked(CONSUMER, 10);

        assertEquals(1, report.stillParked(), "缺口仍在时必须继续挂起，不能强行应用");
        assertEquals(1, integrityService.parkedCount(CONSUMER));
        assertEquals(1, countingConsumer.applied.size());
    }

    // ------------------------------------------------------------------
    // 5. 未订阅类型与授权上下文
    // ------------------------------------------------------------------

    @Test
    void unsubscribedEventTypeIsRecordedAsIgnored() {
        InboxMessage message = message("ReservationExpired", 1, 1);

        InboxProcessor.InboxResult result = processor.process(countingConsumer, message);

        assertEquals(InboxOutcome.IGNORED, result.outcome());
        assertEquals(0, countingConsumer.applied.size());
        assertEquals(1, count("inbox_event WHERE event_id = ? AND outcome = 'IGNORED'", message.eventId()),
                "忽略也要留痕：否则'事件发了没人接'会长期无感");
        assertEquals(0, count("inbox_consumer_position WHERE consumer_name = ?", CONSUMER),
                "忽略的事件不推进位点");
    }

    @Test
    void tenantComesFromMessageNotFromAmbientContext() {
        UUID ambientTenant = UUID.randomUUID();
        UUID messageTenant = UUID.randomUUID();
        TenantContextHolder.bind(new TenantContext(ambientTenant, UUID.randomUUID(), "caller",
                Set.of(), Set.of("reservation:read")));

        InboxMessage message = new InboxMessage(UUID.randomUUID(), messageTenant, UUID.randomUUID(),
                "Reservation", UUID.randomUUID(), 1, 1, "ReservationCreated", 1,
                "{\"no\":\"R-1\"}", OffsetDateTime.now());
        processor.process(countingConsumer, message);

        assertEquals(1, count("inbox_event WHERE event_id = ? AND tenant_id = ?",
                        message.eventId(), messageTenant), "台账必须记消息自己的租户");
        assertEquals(0, count("inbox_event WHERE event_id = ? AND tenant_id = ?",
                message.eventId(), ambientTenant), "绝不能把调用线程上的租户写进去（串租户）");
        assertTrue(TenantContextHolder.current().isPresent(), "处理器不应改写调用方的上下文");
        assertEquals(ambientTenant, TenantContextHolder.current().orElseThrow().tenantId());
    }

    // ------------------------------------------------------------------
    // 6. 真实消费者：订单事件驱动投影重算
    // ------------------------------------------------------------------

    @Test
    void reservationEventRebuildsProjectionThroughRealConsumer() {
        Tenant tenant = organizationService.createTenant("t-inbox", "消费测试租户");
        Hotel hotel = organizationService.createHotel(tenant.id(), null, "h-inbox", "主店", "Asia/Shanghai");
        RoomType type = roomService.createRoomType(tenant.id(), hotel.id(), "std", "标准间", 2, 0, null);
        roomService.createRoom(tenant.id(), hotel.id(), type.id(), "0101", 1, null);

        reservationCommandService.createReservation(tenant.id(),
                new ReservationCommandService.CreateCommand(hotel.id(), "inbox-client",
                        "create-" + UUID.randomUUID(), ReservationSourceType.FRONTDESK,
                        List.of(new ReservationCommandService.CreateCommand.LineCommand(
                                type.id(), StayRange.of(D1, D1.plusDays(2))))));

        InboxMessage event = latestReservationEvent(tenant.id(), hotel.id());
        assertEquals("ReservationCreated", event.eventType());

        InboxProcessor.InboxResult first = processor.process(roomProjectionConsumer, event);

        assertEquals(InboxOutcome.PROCESSED, first.outcome());
        assertEquals(2, count("room_inventory_day WHERE tenant_id = ? AND hotel_id = ? "
                        + "AND reservation_unavailable AND stay_date >= ? AND stay_date < ?",
                tenant.id(), hotel.id(), D1, D1.plusDays(2)),
                "两晚各应有一行标记为被占，实际投影行数不对");
        String rebuiltAt = jdbcTemplate.queryForObject(
                "SELECT max(rebuilt_at)::text FROM room_inventory_day WHERE tenant_id = ?",
                String.class, tenant.id());

        // 重复投递：必须是"重复"，且不得再动投影
        InboxProcessor.InboxResult duplicate = processor.process(roomProjectionConsumer, event);
        assertTrue(duplicate.duplicate());
        assertEquals(rebuiltAt, jdbcTemplate.queryForObject(
                        "SELECT max(rebuilt_at)::text FROM room_inventory_day WHERE tenant_id = ?",
                        String.class, tenant.id()),
                "重复事件不应再触发重算——重算是幂等的，但'有没有再算一次'仍必须可判");
        assertEquals(1, count("inbox_event WHERE consumer_name = ? AND event_id = ?",
                roomProjectionConsumer.name(), event.eventId()));
    }

    @Test
    void projectionConsumerIgnoresUnknownEventType() {
        InboxMessage event = new InboxMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Reservation", UUID.randomUUID(), 1, 1, "SomethingElseHappened", 1,
                "{\"x\":1}", OffsetDateTime.now());

        assertEquals(InboxOutcome.IGNORED, processor.process(roomProjectionConsumer, event).outcome());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 同一聚合上的消息：版本与序号由调用方给，聚合与租户在用例内固定。 */
    private InboxMessage message(String eventType, long version, int ordinal) {
        return new InboxMessage(UUID.randomUUID(), tenantId, hotelId,
                "Reservation", aggregateId, version, ordinal, eventType, 1,
                "{\"no\":\"R-" + version + "-" + ordinal + "\"}", OffsetDateTime.now());
    }

    /** 取最近一条订单事件，转成消费侧消息（真实载荷，不手搓）。 */
    private InboxMessage latestReservationEvent(UUID tenantId, UUID hotelId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT event_id, tenant_id, hotel_id, aggregate_type, aggregate_id, aggregate_version, "
                        + "event_ordinal, event_type, schema_version, payload::text AS payload, occurred_at "
                        + "FROM outbox_event WHERE tenant_id = ? AND hotel_id = ? "
                        + "ORDER BY occurred_at DESC LIMIT 1", tenantId, hotelId);
        return new InboxMessage(
                (UUID) row.get("event_id"),
                (UUID) row.get("tenant_id"),
                (UUID) row.get("hotel_id"),
                (String) row.get("aggregate_type"),
                (UUID) row.get("aggregate_id"),
                ((Number) row.get("aggregate_version")).longValue(),
                ((Number) row.get("event_ordinal")).intValue(),
                (String) row.get("event_type"),
                ((Number) row.get("schema_version")).intValue(),
                (String) row.get("payload"),
                ((java.sql.Timestamp) row.get("occurred_at")).toInstant().atOffset(java.time.ZoneOffset.UTC));
    }

    private int count(String condition, Object... args) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + condition, Integer.class, args);
        return value == null ? 0 : value;
    }

}
