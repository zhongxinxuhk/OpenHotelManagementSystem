package cn.sec.hn.openbackupsaas.integration.service;

import cn.sec.hn.openbackupsaas.integration.domain.ErrorClass;
import cn.sec.hn.openbackupsaas.integration.domain.ExternalTask;
import cn.sec.hn.openbackupsaas.integration.domain.QueryOutcome;
import cn.sec.hn.openbackupsaas.integration.domain.TaskOutcome;
import cn.sec.hn.openbackupsaas.integration.mapper.ExternalTaskMapper;
import cn.sec.hn.openbackupsaas.shared.observability.PmsMetrics;
import cn.sec.hn.openbackupsaas.shared.observability.PmsMetrics.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 外部任务执行器（S2-03）：领取 → 过门 → （未知先查询）→ 调用 → 分类 → 结算。
 *
 * <h2>为什么整个方法没有 {@code @Transactional}</h2>
 * 外部 HTTP 调用必须在事务之外执行。把网络等待放进事务会带来两个后果：一是等待期间一直持有行锁；
 * 二是回滚会把"这次调用真的发出去了"这条事实一起抹掉——第七次超时之后，没人知道对方到底收没收到。
 * 所以这里每一步都是独立语句，租约（而不是事务）负责保证同一时刻只有一个执行者。
 *
 * <h2>未知结果是这一层存在的主要理由</h2>
 * 超时、连接中断、回包丢失都属于"可能已经生效"。处置只有三种：
 * <ol>
 *   <li>能查 → <b>先查</b>：查到成功就收工，查明确实没生效才重发；</li>
 *   <li>不能查 → <b>转人工</b>：既不能确认也没法安全重发；</li>
 *   <li>绝不能"当作失败直接重发"——那是重复扣款/重复占房的标准成因。</li>
 * </ol>
 *
 * <h2>失败分类与终局</h2>
 * <table border="1">
 *   <caption>分类到处置</caption>
 *   <tr><th>分类</th><th>处置</th><th>为什么</th></tr>
 *   <tr><td>SUCCESS</td><td>SUCCEEDED</td><td>—</td></tr>
 *   <tr><td>PERMANENT</td><td>FAILED（终局）</td><td>对方明确拒绝，再试一万次也一样</td></tr>
 *   <tr><td>RETRYABLE / RATE_LIMITED</td><td>退避重试；次数耗尽转 DEAD</td><td>确定没生效，重发安全</td></tr>
 *   <tr><td>UNKNOWN 且能查</td><td>退避后<b>先查</b>；次数耗尽转 DEAD</td><td>不猜</td></tr>
 *   <tr><td>UNKNOWN 且不能查</td><td>MANUAL_REVIEW（立即）</td><td>自动处置只会制造重复或丢失</td></tr>
 * </table>
 */
@Service
public class ExternalTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(ExternalTaskExecutor.class);

    /** 落库的说明文字上限：库里的 last_error 是一句话，不是日志副本。 */
    static final int MAX_DETAIL_LENGTH = 500;

    private final ExternalTaskMapper mapper;
    private final Map<String, ExternalTaskHandler> handlers = new HashMap<>();
    private final ChannelPolicyRegistry policies;
    private final ChannelGate gate;
    private final PmsMetrics metrics;
    private final Clock clock;
    private final Backoff backoff;
    private final int batchSize;
    private final Duration leaseDuration;

    public ExternalTaskExecutor(ExternalTaskMapper mapper,
                                List<ExternalTaskHandler> handlerList,
                                ChannelPolicyRegistry policies,
                                ChannelGate gate,
                                PmsMetrics metrics,
                                Clock clock,
                                @Value("${pms.integration.task.batch-size:20}") int batchSize,
                                @Value("${pms.integration.task.lease-duration:PT2M}") Duration leaseDuration,
                                @Value("${pms.integration.task.base-backoff:PT5S}") Duration baseBackoff,
                                @Value("${pms.integration.task.max-backoff:PT1H}") Duration maxBackoff) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("pms.integration.task.batch-size 必须为正数，收到 " + batchSize);
        }
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "pms.integration.task.lease-duration 必须为正，收到 " + leaseDuration);
        }
        this.mapper = mapper;
        this.policies = policies;
        this.gate = gate;
        this.metrics = metrics;
        this.clock = clock;
        this.backoff = new Backoff(baseBackoff, maxBackoff);
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        for (ExternalTaskHandler handler : handlerList) {
            ExternalTaskHandler previous = handlers.put(handler.taskType(), handler);
            if (previous != null) {
                // 同类型两个处理器：到底调哪一家？宁可启动失败。
                throw new IllegalStateException("任务类型重复注册：" + handler.taskType());
            }
        }
    }

    /**
     * 跑一轮领取并处理。
     *
     * <p>不向外抛异常：单轮失败（数据库抖动、某条任务的意外异常）由调度器记日志后下一轮重来，
     * 任务本身还在库里，租约到期即可重领。
     */
    public ExecutorReport runOnce() {
        UUID leaseToken = UUID.randomUUID();
        List<ExternalTask> claimed = mapper.claimBatch(leaseToken, leaseDuration.toSeconds(), batchSize);
        if (claimed.isEmpty()) {
            return new ExecutorReport(0, 0, 0, 0, 0, 0, 0);
        }

        int succeeded = 0;
        int retried = 0;
        int failed = 0;
        int manual = 0;
        int dead = 0;
        int skipped = 0;
        for (ExternalTask task : claimed) {
            try {
                Step step = dispatch(task, leaseToken);
                // 渠道是运维自定义的配置键前缀，可能带大写；归一化避免 "WeChat"/"wechat" 各算一条。
                metrics.task(task.channel().toLowerCase(Locale.ROOT), outcomeOf(step));
                switch (step) {
                    case SUCCEEDED -> succeeded++;
                    case RETRY -> retried++;
                    case FAILED -> failed++;
                    case MANUAL -> manual++;
                    case DEAD -> dead++;
                    case SKIPPED -> skipped++;
                }
            } catch (RuntimeException ex) {
                // 这里不清理租约：让租约自然过期，另一轮重新领取。
                // 主动释放反而危险——若异常发生在调用之后，释放会让别的实例立刻重发一次。
                log.error("任务 {} 处理中断，租约到期后将重新领取", task.taskId(), ex);
                skipped++;
            }
        }

        ExecutorReport report = new ExecutorReport(claimed.size(), succeeded, retried, failed, manual, dead, skipped);
        if (failed > 0 || manual > 0 || dead > 0) {
            log.info("外部任务本轮：{}", report);
        }
        return report;
    }

    private Step dispatch(ExternalTask task, UUID leaseToken) {
        ExternalTaskHandler handler = handlers.get(task.taskType());
        if (handler == null) {
            return settleManual(task, leaseToken, ErrorClass.PERMANENT,
                    "未注册的任务类型：" + task.taskType() + "（无法自动执行，需人工处置）");
        }

        ChannelPolicy policy = policies.forChannel(task.channel());
        int maxAttempts = Math.min(policy.maxAttempts(), task.maxAttemptsOrDefault());
        Instant now = clock.instant();

        ChannelGate.Decision admission = gate.admit(task.channel(), policy, now);
        if (!admission.allowed()) {
            // 限流/熔断跳过不计入尝试：对方让我们慢一点，不该等于"没机会再试"
            Duration wait = admission.waitAtLeast(now);
            mapper.release(task.taskId(), leaseToken, at(now.plus(wait)), truncate(admission.reason()));
            return Step.SKIPPED;
        }

        // 之前试过且渠道能查：先问清楚上一次到底成没成，再决定要不要重发
        if (task.attemptsOrZero() > 0 && handler.supportsQuery()) {
            QueryOutcome query = queryQuietly(handler, task);
            switch (query.kind()) {
                case FOUND_SUCCESS -> {
                    mapper.settleSucceeded(task.taskId(), leaseToken, query.resultRef());
                    log.info("任务 {} 经查询确认对方已成功，直接结案", task.taskId());
                    return Step.SUCCEEDED;
                }
                case FOUND_FAILED -> {
                    mapper.settleFailed(task.taskId(), leaseToken, ErrorClass.PERMANENT, truncate(query.detail()));
                    return Step.FAILED;
                }
                case NOT_FOUND -> log.debug("任务 {} 查询确认对方无记录，可安全重发", task.taskId());
                case UNKNOWN -> {
                    if (task.attemptsOrZero() >= maxAttempts) {
                        return settleManual(task, leaseToken, ErrorClass.UNKNOWN,
                                "查询未取得结论且次数已用尽，需人工核对：" + query.detail());
                    }
                    return settleRetry(task, leaseToken, ErrorClass.UNKNOWN,
                            "查询未取得结论：" + query.detail(), task.attemptsOrZero());
                }
            }
        }

        Instant started = clock.instant();
        TaskOutcome outcome = executeQuietly(handler, task);
        Instant finished = clock.instant();
        int attemptNo = task.attemptsOrZero() + 1;

        // 先写尝试台账，再改状态：反过来的话，状态说"试了 3 次"而台账只有 2 条，
        // 事后没人能还原第 3 次到底发生了什么。
        mapper.insertAttempt(task.taskId(), attemptNo, at(started), at(finished), outcome.kind().name(),
                outcome.errorClass(), truncate(outcome.detail()), outcome.resultRef());
        mapper.markAttempts(task.taskId(), attemptNo, outcome.errorClass(), truncate(outcome.detail()));
        gate.recordOutcome(task.channel(), policy, outcome, finished);

        boolean exhausted = attemptNo >= maxAttempts;
        return switch (outcome.kind()) {
            case SUCCESS -> {
                mapper.settleSucceeded(task.taskId(), leaseToken, outcome.resultRef());
                yield Step.SUCCEEDED;
            }
            case PERMANENT -> {
                mapper.settleFailed(task.taskId(), leaseToken, ErrorClass.PERMANENT, truncate(outcome.detail()));
                yield Step.FAILED;
            }
            case RATE_LIMITED -> exhausted
                    ? settleDead(task, leaseToken, ErrorClass.RATE_LIMITED, outcome.detail())
                    : settleRetryWith(task, leaseToken, ErrorClass.RATE_LIMITED, outcome.detail(),
                            maxOf(policy.minInterval(), backoff.delayFor(attemptNo)), attemptNo);
            case RETRYABLE -> exhausted
                    ? settleDead(task, leaseToken, ErrorClass.RETRYABLE, outcome.detail())
                    : settleRetryWith(task, leaseToken, ErrorClass.RETRYABLE, outcome.detail(),
                            backoff.delayFor(attemptNo), attemptNo);
            case UNKNOWN -> {
                if (!handler.supportsQuery()) {
                    // 不能查就不能自动重发：可能已经生效
                    yield settleManual(task, leaseToken, ErrorClass.UNKNOWN,
                            "结果未知且渠道不支持查询，需人工核对：" + outcome.detail());
                }
                yield exhausted
                        ? settleDead(task, leaseToken, ErrorClass.UNKNOWN, outcome.detail())
                        : settleRetryWith(task, leaseToken, ErrorClass.UNKNOWN, outcome.detail(),
                                backoff.delayFor(attemptNo), attemptNo);
            }
        };
    }

    private TaskOutcome executeQuietly(ExternalTaskHandler handler, ExternalTask task) {
        try {
            TaskOutcome outcome = handler.execute(task);
            return outcome == null ? TaskOutcome.unknown("处理器返回空结果") : outcome;
        } catch (Exception ex) {
            // 兜底按"未知"处理而不是"可重试"：异常可能发生在请求已发出之后，
            // 当成可重试就会重复执行；当成未知则会先查询或转人工。
            return TaskOutcome.unknown("调用抛出异常：" + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : " - " + ex.getMessage()));
        }
    }

    private QueryOutcome queryQuietly(ExternalTaskHandler handler, ExternalTask task) {
        try {
            QueryOutcome outcome = handler.query(task);
            return outcome == null ? QueryOutcome.unknown("查询返回空结果") : outcome;
        } catch (Exception ex) {
            return QueryOutcome.unknown("查询抛出异常：" + ex.getClass().getSimpleName());
        }
    }

    private Step settleRetry(ExternalTask task, UUID leaseToken, ErrorClass errorClass,
                             String detail, int attemptNo) {
        return settleRetryWith(task, leaseToken, errorClass, detail,
                backoff.delayFor(Math.max(1, attemptNo)), attemptNo);
    }

    private Step settleRetryWith(ExternalTask task, UUID leaseToken, ErrorClass errorClass,
                                 String detail, Duration delay, int attemptNo) {
        Instant next = clock.instant().plus(delay);
        int updated = mapper.settleRetry(task.taskId(), leaseToken, errorClass, truncate(detail), at(next));
        if (updated == 0) {
            // 租约已失效：不做任何覆盖，让新持有者决定（与 Outbox 标记同理）
            log.warn("任务 {} 第 {} 次尝试后回写失败：租约已失效", task.taskId(), attemptNo);
        }
        return Step.RETRY;
    }

    private Step settleManual(ExternalTask task, UUID leaseToken, ErrorClass errorClass, String detail) {
        mapper.settleManual(task.taskId(), leaseToken, errorClass, truncate(detail));
        log.warn("任务 {}（{}）转人工：{}", task.taskId(), task.taskType(), detail);
        return Step.MANUAL;
    }

    private Step settleDead(ExternalTask task, UUID leaseToken, ErrorClass errorClass, String detail) {
        mapper.settleDead(task.taskId(), leaseToken, errorClass, truncate(detail));
        log.error("任务 {}（{}）尝试次数已用尽：{}", task.taskId(), task.taskType(), detail);
        return Step.DEAD;
    }

    private static Duration maxOf(Duration left, Duration right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    /**
     * 处置结论 → 指标结果标签。
     *
     * <p>把 {@code FAILED} 归到 {@link Outcome#CONFLICT} 而不是 ERROR：渠道明确拒绝是
     * <b>业务结论</b>，重试一万次也一样；把它和"超时 / 未知"混成一类，
     * 告警要么吵得没人看，要么钝到真出事时没反应。
     */
    private static Outcome outcomeOf(Step step) {
        return switch (step) {
            case SUCCEEDED -> Outcome.SUCCESS;
            case RETRY -> Outcome.RETRY;
            case FAILED -> Outcome.CONFLICT;
            case MANUAL -> Outcome.MANUAL;
            case DEAD -> Outcome.DEAD;
            case SKIPPED -> Outcome.SKIPPED;
        };
    }

    private static java.time.OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String truncate(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        return detail.length() <= MAX_DETAIL_LENGTH ? detail : detail.substring(0, MAX_DETAIL_LENGTH);
    }

    /** 单条任务的处置结果，仅用于计数与日志。 */
    private enum Step {
        SUCCEEDED,
        RETRY,
        FAILED,
        MANUAL,
        DEAD,
        SKIPPED
    }

    /**
     * 一轮执行的结果。
     *
     * @param claimed   本轮领取条数
     * @param succeeded 成功
     * @param retried   退避后重试
     * @param failed    永久失败（终局）
     * @param manual    转人工
     * @param dead      次数耗尽
     * @param skipped   因限流/熔断跳过，或本代理未处理完
     */
    public record ExecutorReport(int claimed, int succeeded, int retried, int failed,
                                 int manual, int dead, int skipped) {
    }
}
