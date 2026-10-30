package cn.sec.hn.openbackupsaas.journal;

import cn.sec.hn.openbackupsaas.journal.domain.PendingOutboxEvent;
import cn.sec.hn.openbackupsaas.journal.mapper.OutboxPublisherMapper;
import cn.sec.hn.openbackupsaas.journal.messaging.EventPublishException;
import cn.sec.hn.openbackupsaas.journal.messaging.EventPublisher;
import cn.sec.hn.openbackupsaas.journal.messaging.OutboxEnvelope;
import cn.sec.hn.openbackupsaas.journal.service.OutboxBacklogMonitor;
import cn.sec.hn.openbackupsaas.journal.service.OutboxPublisherService;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Outbox 发布器验收（S2-01）。
 *
 * <p>覆盖的三类场景，正是任务里写明必须成立的：
 * <ol>
 *   <li><b>发布前退出</b>：租约到期后重新领取，正常发布一次，不丢；</li>
 *   <li><b>发布成功但标记前退出</b>：允许重发，<b>不能丢</b>（消费侧按 eventId 去重，S2-02）；</li>
 *   <li><b>消息系统停机</b>：事件留库、留失败原因、进入退避，积压指标越界并产生告警计数。</li>
 * </ol>
 *
 * <p>用可编程的 {@link RecordingPublisher} 代替真实 Broker：崩溃场景需要"发出去但没标记"
 * 这种中间态，真实 Broker 无法停在那一刻。真实 RabbitMQ 的确认与持久化另有一个
 * 条件执行的冒烟测试（{@code PMS_IT_RABBIT=true}）。
 */
@TestPropertySource(properties = {
        "pms.outbox.publisher=stub",
        "pms.outbox.batch-size=10",
        "pms.outbox.lease-duration=PT30S",
        "pms.outbox.base-backoff=PT1S",
        "pms.outbox.max-backoff=PT4S",
        "pms.outbox.max-consecutive-failures=3",
        "pms.outbox.monitor.enabled=true",
        "pms.outbox.alert-age=PT1S"
})
class OutboxPublisherIntegrationTest extends PostgresIntegrationTest {

    private static final String AGGREGATE_TYPE = "Reservation";

    @Autowired
    private OutboxPublisherMapper mapper;
    @Autowired
    private OutboxPublisherService publisherService;
    @Autowired
    private OutboxBacklogMonitor monitor;
    @Autowired
    private RecordingPublisher publisher;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * 每个用例前重置可编程桩。
     *
     * <p>它是上下文里的<b>单例</b>，而 Spring 测试上下文在类内复用：不重置的话，
     * "Broker 不可用"会漏到下一个用例，失败信息还会指向莫名其妙的断言
     * （本轮首跑就踩了：claimed=2 却 published=0，原因是上一个用例留下的 failing=true）。
     */
    @org.junit.jupiter.api.BeforeEach
    void resetPublisher() {
        publisher.reset();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubPublisherConfig {
        @Bean
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }
    }

    /** 可编程发布器：记录每一次尝试，可切换为"Broker 不可用"。 */
    static class RecordingPublisher implements EventPublisher {

        final List<OutboxEnvelope> attempts = Collections.synchronizedList(new ArrayList<>());
        final List<OutboxEnvelope> accepted = Collections.synchronizedList(new ArrayList<>());
        volatile boolean failing;
        volatile String failureReason = "Broker 不可用（测试模拟）";

        @Override
        public void publish(OutboxEnvelope envelope) throws EventPublishException {
            attempts.add(envelope);
            if (failing) {
                throw new EventPublishException(failureReason);
            }
            accepted.add(envelope);
        }

        void reset() {
            attempts.clear();
            accepted.clear();
            failing = false;
        }
    }

    // ------------------------------------------------------------------
    // 正常路径
    // ------------------------------------------------------------------

    @Test
    void publishesDueEventsAndMarksThemPublished() {
        UUID tenantId = UUID.randomUUID();
        UUID first = insertEvent(tenantId, UUID.randomUUID(), 1, 1, "ReservationCreated", OffsetDateTime.now());
        UUID second = insertEvent(tenantId, UUID.randomUUID(), 1, 1, "ReservationCreated", OffsetDateTime.now());

        OutboxPublisherService.PublishReport report = publisherService.publishDue();

        assertEquals(2, report.claimed());
        assertEquals(2, report.published());
        assertEquals(0, report.failed());
        assertEquals(2, publisher.accepted.size());
        for (UUID eventId : List.of(first, second)) {
            assertEquals(1, count("published_at IS NOT NULL AND event_id = ?", eventId),
                    "事件 " + eventId + " 应被标记为已发布");
            assertEquals(1, count("lease_token IS NULL AND event_id = ?", eventId), "标记后必须释放租约");
            assertEquals(1, count("attempt_count = 1 AND event_id = ?", eventId), "领取一次即计数一次");
        }
        // 已发布事件不会再被领取
        assertEquals(0, publisherService.publishDue().claimed());
    }

    @Test
    void eventsNotYetDueByBackoffAreNotClaimed() {
        OffsetDateTime past = OffsetDateTime.now().minusMinutes(5);
        UUID eventId = insertEvent(UUID.randomUUID(), UUID.randomUUID(), 1, 1, "ReservationCreated", past);
        jdbcTemplate.update("UPDATE outbox_event SET next_attempt_at = now() + interval '10 minutes' "
                + "WHERE event_id = ?", eventId);

        assertEquals(0, publisherService.publishDue().claimed(),
                "退避期未到的事件不该被领取，否则退避形同虚设");
    }

    @Test
    void sameAggregateIsPublishedInVersionOrder() {
        UUID tenantId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        OffsetDateTime base = OffsetDateTime.now().minusMinutes(1);
        UUID newer = insertEvent(tenantId, aggregateId, 2, 1, "ReservationConfirmed", base.plusSeconds(2));
        UUID older = insertEvent(tenantId, aggregateId, 1, 1, "ReservationCreated", base);
        UUID sameVersionSecondOrdinal = insertEvent(tenantId, aggregateId, 1, 2, "ReservationMarked", base);

        publisherService.publishDue();

        List<UUID> order = publisher.accepted.stream().map(OutboxEnvelope::eventId).toList();
        assertEquals(List.of(older, sameVersionSecondOrdinal, newer), order,
                "同一聚合必须按 版本→序号 递增投递，实际顺序 " + order);
    }

    // ------------------------------------------------------------------
    // 崩溃恢复
    // ------------------------------------------------------------------

    @Test
    void crashBeforePublishIsRecoveredWithoutLoss() {
        UUID eventId = insertEvent(UUID.randomUUID(), UUID.randomUUID(), 1, 1, "ReservationCreated",
                OffsetDateTime.now());

        // Worker A 领到租约后进程被杀：既没发布，也没标记
        List<PendingOutboxEvent> claimed = mapper.claimBatch(UUID.randomUUID(), 30, 10);
        assertEquals(1, claimed.size());
        assertEquals(0, publisher.attempts.size(), "崩溃点必须在发布之前：此时一条都不该发出去");
        expireLease(eventId);

        // 租约到期后 Worker B 接手
        OutboxPublisherService.PublishReport report = publisherService.publishDue();

        assertEquals(1, report.published());
        assertEquals(1, publisher.accepted.size(), "应恰好发布一次，既不丢也不重复");
        assertEquals(1, count("published_at IS NOT NULL AND event_id = ?", eventId));
        assertEquals(1, count("attempt_count = 2 AND event_id = ?", eventId),
                "领取次数记录两次（A 一次、B 一次），这是租约式投递的预期代价");
    }

    @Test
    void crashAfterPublishBeforeMarkCausesRedeliveryButNoLoss() throws Exception {
        UUID eventId = insertEvent(UUID.randomUUID(), UUID.randomUUID(), 1, 1, "ReservationConfirmed",
                OffsetDateTime.now());

        // Worker A：领到租约 → 真的发出去了 → 在标记前进程被杀
        List<PendingOutboxEvent> claimed = mapper.claimBatch(UUID.randomUUID(), 30, 10);
        assertEquals(1, claimed.size());
        publisher.publish(OutboxEnvelope.from(claimed.get(0)));
        assertEquals(1, publisher.accepted.size());
        assertEquals(1, count("published_at IS NULL AND event_id = ?", eventId),
                "此刻库里仍是未发布——这正是危险状态：消息已发出，库里看不出来");
        expireLease(eventId);

        // Worker B 接手：会重发，这是"至少一次"的必然结果
        OutboxPublisherService.PublishReport report = publisherService.publishDue();

        assertEquals(1, report.published());
        assertEquals(2, publisher.accepted.stream().filter(e -> e.eventId().equals(eventId)).count(),
                "同一事件应被投递两次（允许重复，重复由消费侧按 eventId 去重）");
        assertEquals(1, count("published_at IS NOT NULL AND event_id = ?", eventId),
                "关键断言：不丢。库里最终必须有已发布标记");
    }

    @Test
    void leaseHeldByAnotherWorkerIsNotStolen() {
        UUID eventId = insertEvent(UUID.randomUUID(), UUID.randomUUID(), 1, 1, "ReservationCreated",
                OffsetDateTime.now());

        UUID otherToken = UUID.randomUUID();
        assertEquals(1, mapper.claimBatch(otherToken, 30, 10).size(), "第一次领取应成功");
        assertEquals(0, publisherService.publishDue().claimed(),
                "租约未到期时其他 Worker 不应领到同一行（否则同一事件会被并发重发）");
        assertEquals(1, count("lease_token = ? AND event_id = ?", otherToken, eventId));
    }

    @Test
    void markingWithStaleLeaseDoesNotOverwritePublishedState() {
        UUID eventId = insertEvent(UUID.randomUUID(), UUID.randomUUID(), 1, 1, "ReservationCreated",
                OffsetDateTime.now());
        UUID ownerToken = UUID.randomUUID();
        mapper.claimBatch(ownerToken, 30, 10);

        assertEquals(0, mapper.markPublished(eventId, UUID.randomUUID()),
                "拿着别人（或已过期）的租约标记必须 0 行生效，否则会把别人的在途发布写成已发布");
        assertEquals(1, count("published_at IS NULL AND event_id = ?", eventId));
        assertEquals(1, mapper.markPublished(eventId, ownerToken), "持租约者才可标记");
    }

    // ------------------------------------------------------------------
    // Broker 停机
    // ------------------------------------------------------------------

    @Test
    void brokerOutageKeepsEventsInDatabaseWithReasonAndBackoff() {
        UUID tenantId = UUID.randomUUID();
        UUID first = insertEvent(tenantId, UUID.randomUUID(), 1, 1, "ReservationCreated", OffsetDateTime.now());
        UUID second = insertEvent(tenantId, UUID.randomUUID(), 1, 1, "ReservationCreated", OffsetDateTime.now());
        publisher.failing = true;

        OutboxPublisherService.PublishReport report = publisherService.publishDue();

        assertEquals(2, report.claimed());
        assertEquals(0, report.published(), "Broker 不可用时不能有任何一条被标记已发布");
        assertEquals(2, report.failed());
        for (UUID eventId : List.of(first, second)) {
            assertEquals(1, count("published_at IS NULL AND event_id = ?", eventId), "事件必须留在库里");
            assertEquals(1, count("lease_token IS NULL AND event_id = ?", eventId), "失败后必须释放租约");
            assertEquals(1, count("last_error IS NOT NULL AND event_id = ?", eventId),
                    "必须留下失败原因，否则'卡住了'只能靠翻日志");
            assertEquals(1, count("next_attempt_at > now() AND event_id = ?", eventId),
                    "必须进入退避，否则会以轮询间隔持续冲击不可用的 Broker");
        }
        String reason = jdbcTemplate.queryForObject(
                "SELECT last_error FROM outbox_event WHERE event_id = ?", String.class, first);
        assertTrue(reason != null && reason.contains("Broker 不可用"), "失败原因应落库可读，实际：" + reason);
    }

    @Test
    void consecutiveFailuresAbortBatchAndReleaseRemainingLeases() {
        for (int i = 0; i < 8; i++) {
            insertEvent(UUID.randomUUID(), UUID.randomUUID(), 1, 1, "ReservationCreated", OffsetDateTime.now());
        }
        publisher.failing = true;

        OutboxPublisherService.PublishReport report = publisherService.publishDue();

        assertEquals(8, report.claimed());
        assertTrue(report.aborted(), "连续失败达到阈值应中止本轮，避免 8 × 确认超时的无谓等待");
        assertEquals(3, report.failed(), "只应尝试到阈值就停手，实际尝试 " + report.failed());
        assertEquals(0, count("lease_token IS NOT NULL"), "中止时必须释放全部租约，否则事件要等租约到期才能重试");
        assertEquals(5, count("last_error LIKE '%本轮中止%'"), "未尝试的事件也要有可读原因");
        assertEquals(0, count("published_at IS NOT NULL"));
        assertEquals(5, count("next_attempt_at <= now()"),
                "中止释放的 5 条用零退避：它们并未真正尝试过，不该背上长退避");
        assertEquals(3, count("next_attempt_at > now()"),
                "真正尝试过的 3 条要按退避推迟，否则下一轮立刻又撞上不可用的 Broker");
    }

    @Test
    void backlogMonitorReportsAgeAndRaisesAlert() {
        UUID eventId = insertEvent(UUID.randomUUID(), UUID.randomUUID(), 1, 1, "ReservationCreated",
                OffsetDateTime.now().minusHours(2));

        monitor.refresh();

        OutboxBacklogMonitor.Snapshot snapshot = monitor.snapshot();
        assertEquals(1, snapshot.pending());
        assertTrue(snapshot.oldestAgeSeconds() > 3600,
                "最老事件年龄应反映真实等待时长，实际 " + snapshot.oldestAgeSeconds());
        assertTrue(snapshot.breached(), "超过阈值必须判为越界");
        assertTrue(meterRegistry.get("pms.outbox.backlog_breach").counter().count() >= 1,
                "越界必须留下计数，否则'告警'只是日志里的一行字");
        assertTrue(meterRegistry.get("pms.outbox.pending").gauge().value() >= 1,
                "待发布条数应作为指标暴露");

        // 发布掉之后越界应解除
        publisherService.publishDue();
        monitor.refresh();
        assertFalse(monitor.snapshot().breached(), "积压清空后必须能自行恢复，否则告警永远不会停止");
        assertEquals(0, monitor.snapshot().pending());
        assertEquals(1, count("published_at IS NOT NULL AND event_id = ?", eventId));
    }

    @Test
    void brokerRecoversAndBacklogDrains() {
        UUID tenantId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            insertEvent(tenantId, UUID.randomUUID(), 1, 1, "ReservationCreated", OffsetDateTime.now());
        }
        publisher.failing = true;
        publisherService.publishDue();

        // Broker 恢复，且退避时间已过（模拟时间流逝，不真的等）
        publisher.failing = false;
        jdbcTemplate.update("UPDATE outbox_event SET next_attempt_at = now() WHERE published_at IS NULL");

        OutboxPublisherService.PublishReport report = publisherService.publishDue();

        assertEquals(3, report.published());
        assertEquals(0, count("published_at IS NULL"), "恢复后积压必须能排空");
    }

    // ------------------------------------------------------------------
    // 领取互斥
    // ------------------------------------------------------------------

    @Test
    void twoWorkersNeverClaimTheSameEvent() throws Exception {
        for (int i = 0; i < 20; i++) {
            insertEvent(UUID.randomUUID(), UUID.randomUUID(), 1, 1, "ReservationCreated", OffsetDateTime.now());
        }
        // 显式指定租约令牌：这样断言可以落到数据库事实上，而不是只看 JVM 里返回的列表
        UUID tokenA = UUID.randomUUID();
        UUID tokenB = UUID.randomUUID();

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<List<PendingOutboxEvent>> rounds = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<PendingOutboxEvent>> a = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return mapper.claimBatch(tokenA, 30, 10);
            });
            Future<List<PendingOutboxEvent>> b = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return mapper.claimBatch(tokenB, 30, 10);
            });
            rounds.add(a.get(30, TimeUnit.SECONDS));
            rounds.add(b.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        // 关键不变量（都已反向验证过）：
        // · 不重复：把领取 SQL 的租约条件删掉，这里立刻报 10 条重复；
        //   而删掉 SKIP LOCKED 时仍然通过——所以去重靠租约条件，SKIP LOCKED 只负责"不互相等待"。
        // · 不超批大小：单次领取是上限，不是保证。实测出现过 10 + 7（被跳过的行占掉了限额窗口），
        //   也出现过一方 0 条（限额窗口整个落在对方锁住的行上）。这些都正常，下一轮会领到剩下的。
        Set<UUID> union = new HashSet<>();
        int duplicates = 0;
        for (List<PendingOutboxEvent> batch : rounds) {
            for (PendingOutboxEvent event : batch) {
                if (!union.add(event.eventId())) {
                    duplicates++;
                }
            }
            // 只是快速反馈；权威断言在下面按数据库核对
            assertTrue(batch.size() <= 10, "单次领取不得超过批大小，实际 " + batch.size()
                    + "；本轮各批大小=" + rounds.stream().map(List::size).toList()
                    + "，A 持有 " + count("lease_token = ?", tokenA)
                    + " 行、B 持有 " + count("lease_token = ?", tokenB)
                    + " 行，全库已租 " + count("lease_token IS NOT NULL")
                    + " 行、attempt_count>1 的 " + count("attempt_count > 1") + " 行");
        }
        assertEquals(0, duplicates,
                "同一条事件不得被两个 Worker 同时领到（靠租约条件判定，SKIP LOCKED 只负责不互相等待）");

        // 不按"每人各领 10 条"断言：并发下任何一方都可能领到少于批大小（甚至 0），
        // 那是限额窗口与行锁交错的结果，不是缺陷。真正要证明的是"没有一行被领两次"。
        int leasedByA = count("lease_token = ?", tokenA);
        int leasedByB = count("lease_token = ?", tokenB);
        assertTrue(leasedByA <= 10, "A 单次领取不得超过批大小，实际 " + leasedByA);
        assertTrue(leasedByB <= 10, "B 单次领取不得超过批大小，实际 " + leasedByB);
        assertEquals(leasedByA + leasedByB, count("lease_token IS NOT NULL"),
                "所有被领取的行都必须归属本轮两个令牌之一（出现第三个值说明有别的写入路径）");
        // attempt_count 是"被领取次数"，本轮每条最多加一次。若某行 =2，说明被两个 Worker 同时领到。
        assertEquals(union.size(), count("attempt_count = 1"),
                "本轮被领到的行必须恰好各记一次领取；实际 union=" + union.size()
                        + " attempt_count=1 的行=" + count("attempt_count = 1"));

        // 后续轮次把剩下的领完（若上一轮因竞争没领满）：累计覆盖全部 20 条且不重复
        for (int round = 0; round < 10 && union.size() < 20; round++) {
            for (PendingOutboxEvent event : mapper.claimBatch(UUID.randomUUID(), 30, 10)) {
                assertTrue(union.add(event.eventId()), "重复轮次也不得重复领取");
            }
        }
        assertEquals(20, union.size(), "多轮领取最终应覆盖全部事件，不丢");
        assertEquals(20, count("lease_token IS NOT NULL"), "领取后每行都应有租约");
        assertEquals(0, count("attempt_count > 1"), "全程不得有任何一行被领取两次（同一事件并发重发的根因）");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private UUID insertEvent(UUID tenantId, UUID aggregateId, long aggregateVersion, int ordinal,
                             String eventType, OffsetDateTime occurredAt) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO outbox_event (event_id, tenant_id, hotel_id, aggregate_type, "
                        + "aggregate_id, aggregate_version, event_ordinal, event_type, schema_version, payload, "
                        + "occurred_at, attempt_count, next_attempt_at) "
                        + "VALUES (?, ?, NULL, ?, ?, ?, ?, ?, 1, ?::jsonb, ?, 0, ?)",
                eventId, tenantId, AGGREGATE_TYPE, aggregateId, aggregateVersion, ordinal, eventType,
                "{\"reservationNo\":\"R-1\"}", occurredAt, occurredAt);
        return eventId;
    }

    /** 模拟"持有租约的进程已死、租约到期"：直接改库比 sleep 30 秒更确定。 */
    private void expireLease(UUID eventId) {
        jdbcTemplate.update("UPDATE outbox_event SET lease_until = now() - interval '1 second' "
                + "WHERE event_id = ?", eventId);
    }

    private int count(String condition, Object... args) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE " + condition, Integer.class, args);
        return value == null ? 0 : value;
    }

    @Test
    void publishDoesNotRewritePayload() {
        UUID eventId = insertEvent(UUID.randomUUID(), UUID.randomUUID(), 1, 1, "ReservationCreated",
                OffsetDateTime.now());
        String before = jdbcTemplate.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE event_id = ?", String.class, eventId);

        publisherService.publishDue();

        String after = jdbcTemplate.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE event_id = ?", String.class, eventId);
        assertEquals(before, after, "载荷是已定案的事实，发布过程不得改写");
        assertNotNull(after);
        assertNull(jdbcTemplate.queryForObject(
                "SELECT last_error FROM outbox_event WHERE event_id = ?", String.class, eventId),
                "发布成功后不该留下上次的失败原因");
    }
}
