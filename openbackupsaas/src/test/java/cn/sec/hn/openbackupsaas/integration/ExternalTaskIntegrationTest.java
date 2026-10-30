package cn.sec.hn.openbackupsaas.integration;

import cn.sec.hn.openbackupsaas.integration.domain.ExternalTask;
import cn.sec.hn.openbackupsaas.integration.domain.QueryOutcome;
import cn.sec.hn.openbackupsaas.integration.domain.TaskOutcome;
import cn.sec.hn.openbackupsaas.integration.domain.TaskStatus;
import cn.sec.hn.openbackupsaas.integration.mapper.ExternalTaskMapper;
import cn.sec.hn.openbackupsaas.integration.service.ChannelGate;
import cn.sec.hn.openbackupsaas.integration.service.ExternalTaskExecutor;
import cn.sec.hn.openbackupsaas.integration.service.ExternalTaskHandler;
import cn.sec.hn.openbackupsaas.integration.service.ExternalTaskService;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部任务与未知结果处置的集成测试（S2-03），**必须连真实 PostgreSQL**。
 *
 * <p>逐条对着任务验收点：
 * <ol>
 *   <li>执行器崩溃后可重新领取；</li>
 *   <li>未知结果先查询，或按对方协议安全重试；</li>
 *   <li>不具备幂等/查询能力的操作转人工处置；</li>
 *   <li>永久错误不会无限重试。</li>
 * </ol>
 * 另外覆盖退避抖动区间、最大次数、每渠道限流与熔断。
 */
@TestPropertySource(properties = {
        "pms.integration.task.base-backoff=PT10S",
        "pms.integration.task.max-backoff=PT20S",
        "pms.integration.channel.fragile.circuit-threshold=2",
        "pms.integration.channel.fragile.circuit-cooldown=PT30S",
        "pms.integration.channel.slow.min-interval=PT10S"
})
class ExternalTaskIntegrationTest extends PostgresIntegrationTest {

    private static final String TASK_TYPE = "stub-call";
    private static final String CHANNEL = "stub-channel";
    private static final String FRAGILE = "fragile";
    private static final String SLOW = "slow";

    @Autowired
    private ExternalTaskExecutor executor;
    @Autowired
    private ExternalTaskService service;
    @Autowired
    private ExternalTaskMapper mapper;
    @Autowired
    private ChannelGate gate;
    @Autowired
    private StubHandler handler;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TestConfiguration(proxyBeanMethods = false)
    static class StubHandlerConfig {
        @Bean
        StubHandler stubHandler() {
            return new StubHandler();
        }
    }

    /** 可编程处理器：记录调用次数、按队列返回预设结果。 */
    static class StubHandler implements ExternalTaskHandler {

        final Queue<TaskOutcome> planned = new ConcurrentLinkedQueue<>();
        final AtomicInteger executeCalls = new AtomicInteger();
        final AtomicInteger queryCalls = new AtomicInteger();
        volatile boolean querySupport = true;
        volatile QueryOutcome queryResult = QueryOutcome.notFound();

        @Override
        public String taskType() {
            return TASK_TYPE;
        }

        @Override
        public boolean supportsQuery() {
            return querySupport;
        }

        @Override
        public TaskOutcome execute(ExternalTask task) {
            executeCalls.incrementAndGet();
            TaskOutcome next = planned.poll();
            return next == null ? TaskOutcome.success("ext-" + executeCalls.get()) : next;
        }

        @Override
        public QueryOutcome query(ExternalTask task) {
            queryCalls.incrementAndGet();
            return queryResult;
        }

        void reset() {
            planned.clear();
            executeCalls.set(0);
            queryCalls.set(0);
            querySupport = true;
            queryResult = QueryOutcome.notFound();
        }
    }

    @BeforeEach
    void resetState() {
        handler.reset();
        // ChannelGate 是单例，跨用例残留的熔断/限流状态会让后一个用例莫名被跳过
        gate.reset(CHANNEL);
        gate.reset(FRAGILE);
        gate.reset(SLOW);
    }

    // ------------------------------------------------------------------
    // 1. 正常成功
    // ------------------------------------------------------------------

    @Test
    void successMarksSucceededAndRecordsAttempt() {
        UUID taskId = enqueue(CHANNEL, 3);

        ExternalTaskExecutor.ExecutorReport report = executor.runOnce();

        assertEquals(1, report.succeeded());
        ExternalTask task = service.find(taskId);
        assertEquals(TaskStatus.SUCCEEDED, task.status());
        assertEquals(1, task.attemptsOrZero());
        assertNotNull(task.resultRef());
        assertEquals(1, count("external_task_attempt WHERE task_id = ?", taskId), "每次真实调用都要有台账");
        assertEquals(1, count("external_task WHERE task_id = ? AND completed_at IS NOT NULL", taskId),
                "终态必须带完成时间");
        assertNull(task.leaseToken(), "结算后必须释放租约");
    }

    // ------------------------------------------------------------------
    // 2. 退避与抖动
    // ------------------------------------------------------------------

    @Test
    void retryableSchedulesBackoffWithinJitterBounds() {
        UUID taskId = enqueue(CHANNEL, 3);
        handler.planned.add(TaskOutcome.retryable("连接被拒"));

        executor.runOnce();

        ExternalTask task = service.find(taskId);
        assertEquals(TaskStatus.PENDING, task.status());
        assertEquals(1, task.attemptsOrZero());
        long waitSeconds = java.time.Duration.between(OffsetDateTime.now(), task.nextAttemptAt()).toSeconds();
        // 基数 10 秒、抖动 ±20%：允许调度与时钟的少量偏差，但必须落在区间附近
        assertTrue(waitSeconds >= 7 && waitSeconds <= 13,
                "退避应落在 8–12 秒附近（含抖动），实际 " + waitSeconds + " 秒");
    }

    // ------------------------------------------------------------------
    // 3. 永久错误不会无限重试
    // ------------------------------------------------------------------

    @Test
    void permanentErrorIsTerminalAndNotRetried() {
        UUID taskId = enqueue(CHANNEL, 5);
        handler.planned.add(TaskOutcome.permanent("400 参数校验失败"));

        executor.runOnce();

        ExternalTask task = service.find(taskId);
        assertEquals(TaskStatus.FAILED, task.status());
        assertEquals(1, task.attemptsOrZero());

        // 再跑一轮：终态任务不该被领到
        ExternalTaskExecutor.ExecutorReport second = executor.runOnce();
        assertEquals(0, second.claimed(), "终态任务不得再被领取，否则就是无限重试");
        assertEquals(1, handler.executeCalls.get(), "永久错误只应调用一次");
    }

    // ------------------------------------------------------------------
    // 4. 未知结果先查询（核心）
    // ------------------------------------------------------------------

    @Test
    void unknownWithQuerySupportQueriesInsteadOfResending() {
        UUID taskId = enqueue(CHANNEL, 5);
        handler.planned.add(TaskOutcome.unknown("读超时，回包未知"));

        executor.runOnce();
        assertEquals(TaskStatus.PENDING, service.find(taskId).status(), "未知结果应退避后重试，而不是终局");
        assertEquals(1, handler.executeCalls.get());

        // 退避到期（用例直接改库，不真的等 10 秒），对方随后能查到"其实成功了"
        makeDue(taskId);
        handler.queryResult = QueryOutcome.foundSuccess("ext-confirmed-by-query");

        executor.runOnce();

        ExternalTask task = service.find(taskId);
        assertEquals(TaskStatus.SUCCEEDED, task.status(), "查询确认已成功，应直接结案");
        assertEquals(1, handler.executeCalls.get(), "关键：查询确认成功后不得再调用一次（那就是重复执行）");
        assertEquals(1, handler.queryCalls.get());
        assertEquals("ext-confirmed-by-query", task.resultRef());
        assertEquals(1, count("external_task_attempt WHERE task_id = ?", taskId),
                "查询不是一次新的调用尝试，不该多记一条");
    }

    @Test
    void unknownThenQueryNotFoundAllowsSafeResend() {
        UUID taskId = enqueue(CHANNEL, 5);
        handler.planned.add(TaskOutcome.unknown("读超时"));
        executor.runOnce();
        makeDue(taskId);
        handler.queryResult = QueryOutcome.notFound();
        handler.planned.add(TaskOutcome.success("ext-after-resend"));

        executor.runOnce();

        assertEquals(TaskStatus.SUCCEEDED, service.find(taskId).status());
        assertEquals(2, handler.executeCalls.get(), "查询确认对方无记录后才可以重发");
        assertEquals(2, count("external_task_attempt WHERE task_id = ?", taskId));
    }

    // ------------------------------------------------------------------
    // 5. 不能查询就必须转人工
    // ------------------------------------------------------------------

    @Test
    void unknownWithoutQuerySupportGoesManualReview() {
        UUID taskId = enqueue(CHANNEL, 5);
        handler.querySupport = false;
        handler.planned.add(TaskOutcome.unknown("连接中断，请求可能已送达"));

        executor.runOnce();

        ExternalTask task = service.find(taskId);
        assertEquals(TaskStatus.MANUAL_REVIEW, task.status(),
                "既查不到又不能安全重发，只能转人工——自动重发就是重复扣款的标准成因");
        assertEquals(1, handler.executeCalls.get(), "不得自动重发");
        assertEquals(0, handler.queryCalls.get(), "不支持查询就不该去查");
    }

    // ------------------------------------------------------------------
    // 6. 执行器崩溃后重新领取
    // ------------------------------------------------------------------

    @Test
    void crashRecoveryReclaimsRunningTaskWithExpiredLease() {
        UUID taskId = enqueue(CHANNEL, 3);
        // 模拟"上一个执行器领走了任务然后进程被杀"：状态 RUNNING、租约未释放
        assertEquals(1, mapper.claimBatch(UUID.randomUUID(), 120, 10).size());
        assertEquals(TaskStatus.RUNNING, service.find(taskId).status());

        // 租约未过期时别人领不到（不能抢正在执行的调用）
        assertEquals(0, executor.runOnce().claimed(), "租约有效期内不得被重复领取");

        expireLease(taskId);
        ExternalTaskExecutor.ExecutorReport report = executor.runOnce();

        assertEquals(1, report.claimed(), "租约过期后必须能重新领取——否则崩溃过的任务永远停在 RUNNING");
        assertEquals(TaskStatus.SUCCEEDED, service.find(taskId).status());
    }

    // ------------------------------------------------------------------
    // 7. 最大次数
    // ------------------------------------------------------------------

    @Test
    void maxAttemptsExhaustedGoesDead() {
        UUID taskId = enqueue(CHANNEL, 2);
        handler.planned.add(TaskOutcome.retryable("503"));
        handler.planned.add(TaskOutcome.retryable("503"));

        executor.runOnce();
        makeDue(taskId);
        executor.runOnce();

        ExternalTask task = service.find(taskId);
        assertEquals(TaskStatus.DEAD, task.status(), "次数耗尽应转 DEAD（可人工重放），而不是悄悄消失");
        assertEquals(2, task.attemptsOrZero());
        assertEquals(2, count("external_task_attempt WHERE task_id = ?", taskId));
    }

    // ------------------------------------------------------------------
    // 8. 每渠道限流
    // ------------------------------------------------------------------

    @Test
    void rateLimitedChannelSkipsWithoutConsumingAttempts() {
        UUID first = enqueue(SLOW, 3);
        handler.planned.add(TaskOutcome.retryable("429"));
        executor.runOnce();
        assertEquals(1, service.find(first).attemptsOrZero());

        UUID second = enqueue(SLOW, 3);
        ExternalTaskExecutor.ExecutorReport report = executor.runOnce();

        assertEquals(1, report.skipped(), "最小间隔内应跳过，不打对方");
        ExternalTask skipped = service.find(second);
        assertEquals(TaskStatus.PENDING, skipped.status());
        assertEquals(0, skipped.attemptsOrZero(),
                "被限流跳过的轮次不计入尝试，否则'对方让我慢点'会变成'我没机会再试'");
        assertEquals(1, handler.executeCalls.get(), "第二个任务一次都不该调用");
        assertTrue(skipped.nextAttemptAt() != null);
    }

    // ------------------------------------------------------------------
    // 9. 熔断隔离
    // ------------------------------------------------------------------

    @Test
    void circuitOpensAfterConsecutiveFailuresAndSkipsFurtherCalls() {
        UUID t1 = enqueue(FRAGILE, 5);
        UUID t2 = enqueue(FRAGILE, 5);
        UUID t3 = enqueue(FRAGILE, 5);
        handler.planned.add(TaskOutcome.retryable("503"));
        handler.planned.add(TaskOutcome.retryable("503"));

        ExternalTaskExecutor.ExecutorReport report = executor.runOnce();

        assertTrue(gate.isOpen(FRAGILE, Instant.now()), "连续失败达到阈值后该渠道应熔断");
        assertEquals(2, handler.executeCalls.get(), "熔断后不再调用：第 3 条应被跳过");
        assertEquals(1, report.skipped(), "报告中要能看出有任务被熔断挡下");
        assertEquals(0, service.find(t3).attemptsOrZero(), "被熔断挡下的任务不消耗尝试次数");
        assertNotNull(service.find(t1).nextAttemptAt());
        assertNotNull(service.find(t2).nextAttemptAt());
    }

    // ------------------------------------------------------------------
    // 10. 人工处置（补偿接口）
    // ------------------------------------------------------------------

    @Test
    void manualResolutionOnlyFromManualReview() {
        UUID taskId = enqueue(CHANNEL, 5);
        handler.querySupport = false;
        handler.planned.add(TaskOutcome.unknown("超时且无法查询"));
        executor.runOnce();
        assertEquals(TaskStatus.MANUAL_REVIEW, service.find(taskId).status());

        assertTrue(service.resolveManually(taskId, true, "ext-manual-1", "ops-1", "已与渠道核实成功"),
                "人工结案应生效");
        ExternalTask resolved = service.find(taskId);
        assertEquals(TaskStatus.SUCCEEDED, resolved.status());
        assertEquals("ext-manual-1", resolved.resultRef());

        // 已经在跑的/待执行的任务不允许被人工改判（会与机器的调用打架）
        UUID pendingTask = enqueue(CHANNEL, 5);
        assertFalse(service.resolveManually(pendingTask, true, null, "ops-1", "越权改判"),
                "非 MANUAL_REVIEW 状态不得被人工改判");
        assertEquals(TaskStatus.PENDING, service.find(pendingTask).status());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private UUID enqueue(String channel, int maxAttempts) {
        return service.enqueue(new ExternalTaskService.NewTask(
                UUID.randomUUID(), UUID.randomUUID(), TASK_TYPE, channel,
                "idem-" + UUID.randomUUID(), "{\"orderNo\":\"R-1\"}", maxAttempts));
    }

    private void makeDue(UUID taskId) {
        jdbcTemplate.update("UPDATE external_task SET next_attempt_at = now() WHERE task_id = ?", taskId);
    }

    private void expireLease(UUID taskId) {
        jdbcTemplate.update("UPDATE external_task SET lease_until = now() - interval '1 second' "
                + "WHERE task_id = ?", taskId);
    }

    private int count(String condition, Object... args) {
        Integer value = jdbcTemplate.queryForObject("SELECT count(*) FROM " + condition, Integer.class, args);
        return value == null ? 0 : value;
    }
}
