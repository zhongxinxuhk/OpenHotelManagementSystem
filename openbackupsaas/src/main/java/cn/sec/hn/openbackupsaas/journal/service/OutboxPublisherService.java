package cn.sec.hn.openbackupsaas.journal.service;

import cn.sec.hn.openbackupsaas.journal.domain.PendingOutboxEvent;
import cn.sec.hn.openbackupsaas.journal.mapper.OutboxPublisherMapper;
import cn.sec.hn.openbackupsaas.journal.messaging.EventPublishException;
import cn.sec.hn.openbackupsaas.journal.messaging.EventPublisher;
import cn.sec.hn.openbackupsaas.journal.messaging.OutboxEnvelope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Outbox 发布器（S2-01）。
 *
 * <h2>为什么不是"一个事务把发布和标记包起来"</h2>
 * 发布是网络调用。把它包进事务，意味着在等待 Broker 确认的整段时间里持有行锁：
 * 一个慢 Broker 就能把所有 Worker 拖死，而且事务回滚会连"失败记录"一起丢掉——
 * 恰恰在最需要诊断的时候没有诊断信息。所以这里刻意做成三步、每步独立提交：
 *
 * <pre>
 *   ① 领取（一条 UPDATE，提交，租约落库）
 *   ② 发布（网络调用，无事务、无行锁）
 *   ③ 标记（一条 UPDATE，带租约校验）
 * </pre>
 *
 * <h2>崩溃点与后果（验收要求逐条对上）</h2>
 * <table border="1">
 *   <caption>崩溃恢复语义</caption>
 *   <tr><th>崩溃位置</th><th>库里状态</th><th>恢复方式</th><th>后果</th></tr>
 *   <tr><td>①之后、②之前</td><td>有租约、未发布</td><td>租约到期后重新领取</td><td>正常发布一次，<b>不丢</b></td></tr>
 *   <tr><td>②之后、③之前</td><td>有租约、未发布（但已发出）</td><td>租约到期后重新领取</td><td><b>重发一次</b>，消费方去重（S2-02）</td></tr>
 *   <tr><td>③之后</td><td>已发布</td><td>不再参与领取</td><td>—</td></tr>
 * </table>
 *
 * <p>也就是说：<b>允许重复，不允许丢失</b>。这个取舍不是妥协，而是选择"至少一次"后
 * 必然的结果——去重责任在消费侧，用 {@code eventId} 做幂等键。
 *
 * <h2>租约丢失时为什么不覆盖</h2>
 * 标记语句带 {@code lease_token} 条件，返回 0 行说明"这条已经不归我了"（租约过期被别人领走，
 * 或已被别人标记发布）。此时若仍写 {@code published_at}，会把别人的在途发布变成"已发布"，
 * 一旦对方随后失败，这条事件就永远丢了。所以这里只记日志并计入 {@code leaseLost}。
 */
@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    /** 落库错误文本上限：库里的 last_error 是给人看的一句话，不是日志副本。 */
    static final int MAX_ERROR_LENGTH = 500;

    /** 退避指数上限，避免 2^n 溢出；实际还会被 maxBackoff 截断。 */
    private static final int MAX_BACKOFF_EXPONENT = 20;

    private final OutboxPublisherMapper mapper;
    private final EventPublisher publisher;
    private final int batchSize;
    private final Duration leaseDuration;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final int maxConsecutiveFailures;
    private final Counter publishedCounter;
    private final Counter failedCounter;

    public OutboxPublisherService(OutboxPublisherMapper mapper,
                                  EventPublisher publisher,
                                  MeterRegistry meterRegistry,
                                  @Value("${pms.outbox.batch-size:50}") int batchSize,
                                  @Value("${pms.outbox.lease-duration:PT30S}") Duration leaseDuration,
                                  @Value("${pms.outbox.base-backoff:PT2S}") Duration baseBackoff,
                                  @Value("${pms.outbox.max-backoff:PT5M}") Duration maxBackoff,
                                  @Value("${pms.outbox.max-consecutive-failures:3}") int maxConsecutiveFailures) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("pms.outbox.batch-size 必须为正数，收到 " + batchSize);
        }
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("pms.outbox.lease-duration 必须为正，收到 " + leaseDuration);
        }
        if (maxConsecutiveFailures <= 0) {
            throw new IllegalArgumentException(
                    "pms.outbox.max-consecutive-failures 必须为正数，收到 " + maxConsecutiveFailures);
        }
        this.mapper = mapper;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.baseBackoff = baseBackoff;
        this.maxBackoff = maxBackoff;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
        this.publishedCounter = Counter.builder("pms.outbox.published")
                .description("已获得 Broker 确认并标记发布的事件数")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("pms.outbox.failed")
                .description("本轮未获确认、已回写退避的事件数")
                .register(meterRegistry);
    }

    /**
     * 跑一轮：领取一批、逐条发布、按结果标记。
     *
     * <p>不抛异常给调用方：单轮失败（含数据库抖动）由调度器记日志后下一轮重来，
     * 事件本身还在库里，重试就是恢复手段。
     */
    public PublishReport publishDue() {
        UUID leaseToken = UUID.randomUUID();
        List<PendingOutboxEvent> claimed = new ArrayList<>(
                mapper.claimBatch(leaseToken, leaseDuration.toSeconds(), batchSize));
        if (claimed.isEmpty()) {
            return new PublishReport(0, 0, 0, 0, false);
        }

        claimed.sort(aggregateOrder());
        int published = 0;
        int failed = 0;
        int leaseLost = 0;
        int consecutiveFailures = 0;
        boolean aborted = false;

        for (int index = 0; index < claimed.size(); index++) {
            PendingOutboxEvent event = claimed.get(index);

            if (consecutiveFailures >= maxConsecutiveFailures) {
                // Broker 整体不可用：继续逐条硬试只会把整批耗时拉成 N × 确认超时。
                // 剩余事件立刻释放租约（退避 0），下一轮再试；它们并没有真正被发布过。
                abortRemaining(claimed.subList(index, claimed.size()), leaseToken);
                aborted = true;
                break;
            }

            try {
                publisher.publish(OutboxEnvelope.from(event));
            } catch (EventPublishException ex) {
                consecutiveFailures++;
                failed++;
                failedCounter.increment();
                long backoff = backoffSeconds(event.attemptCount());
                int updated = mapper.markFailed(event.eventId(), leaseToken, truncate(ex.getMessage()), backoff);
                if (updated == 0) {
                    leaseLost++;
                }
                log.warn("事件 {} 发布失败（第 {} 次领取），{} 秒后重试：{}",
                        event.eventId(), event.attemptCount(), backoff, ex.getMessage());
                continue;
            }

            consecutiveFailures = 0;
            int updated = mapper.markPublished(event.eventId(), leaseToken);
            if (updated == 0) {
                leaseLost++;
                // 已经发出去了但没能标记：事件会在租约到期后被再次发布。这不是错误，
                // 是至少一次语义的一部分；但它意味着"重复"，必须留痕让运维能对上账。
                log.warn("事件 {} 已发布但标记未生效（租约 {} 已失效），该事件可能被重发",
                        event.eventId(), leaseToken);
            } else {
                published++;
                publishedCounter.increment();
            }
        }

        if (failed > 0 || aborted || leaseLost > 0) {
            log.info("Outbox 本轮：领取 {} 发布 {} 失败 {} 租约丢失 {} 中止={}",
                    claimed.size(), published, failed, leaseLost, aborted);
        }
        return new PublishReport(claimed.size(), published, failed, leaseLost, aborted);
    }

    /**
     * 批次内保序：先按发生时间，再按聚合内版本与序号。
     *
     * <p>为什么必须在 Java 里排：{@code UPDATE ... RETURNING} 的行顺序没有保证。
     * 消费侧对同一聚合的状态迁移要求"版本递增"（S2-02 的乱序处理），
     * 这里能免费给的顺序就不要留给下游去纠正。
     */
    private static Comparator<PendingOutboxEvent> aggregateOrder() {
        Comparator<PendingOutboxEvent> byOccurred = Comparator.comparing(PendingOutboxEvent::occurredAt);
        return byOccurred
                .thenComparing(PendingOutboxEvent::aggregateId)
                .thenComparing(e -> e.aggregateVersion() == null ? 0L : e.aggregateVersion())
                .thenComparing(e -> e.eventOrdinal() == null ? 0 : e.eventOrdinal())
                .thenComparing(PendingOutboxEvent::eventId);
    }

    private void abortRemaining(List<PendingOutboxEvent> remaining, UUID leaseToken) {
        String reason = "Broker 连续失败，本轮中止（未发布）";
        for (PendingOutboxEvent event : remaining) {
            mapper.markFailed(event.eventId(), leaseToken, reason, 0L);
        }
        log.error("Outbox 发布连续失败达到阈值，本轮剩余 {} 条事件未尝试发布，已释放租约等待下一轮",
                remaining.size());
    }

    /** 指数退避：base × 2^(attempt-1)，上限 maxBackoff。 */
    long backoffSeconds(Integer attemptCount) {
        int attempt = attemptCount == null || attemptCount < 1 ? 1 : attemptCount;
        long multiplier = 1L << Math.min(attempt - 1, MAX_BACKOFF_EXPONENT);
        long seconds = baseBackoff.toSeconds() * multiplier;
        return Math.min(seconds, Math.max(maxBackoff.toSeconds(), baseBackoff.toSeconds()));
    }

    private static String truncate(String message) {
        String text = message == null || message.isBlank() ? "未知错误" : message;
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }

    /**
     * 一轮发布的结果。
     *
     * @param claimed    本轮领取条数
     * @param published  已获确认并标记成功
     * @param failed     未获确认、已回写退避
     * @param leaseLost  发布成功但标记未生效（可能重发）或标记时租约已失
     * @param aborted    是否因连续失败提前中止（剩余事件未尝试）
     */
    public record PublishReport(int claimed, int published, int failed, int leaseLost, boolean aborted) {
    }
}
