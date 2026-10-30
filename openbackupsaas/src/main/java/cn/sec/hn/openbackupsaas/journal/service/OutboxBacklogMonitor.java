package cn.sec.hn.openbackupsaas.journal.service;

import cn.sec.hn.openbackupsaas.journal.mapper.OutboxPublisherMapper;
import cn.sec.hn.openbackupsaas.shared.runtime.WorkerRoleCondition;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox 积压与事件年龄（S2-01 的交付项：积压与事件年龄指标）。
 *
 * <h2>为什么必须单独有"年龄"</h2>
 * 只报"待发布条数"会漏掉最危险的情形：条数一直很小，但每条都等了半小时。
 * 这通常意味着 Broker 时通时不通，事件在被反复重试——单看计数完全正常。
 * 事件年龄（最老一条等了多久）才能把这个说出来。
 *
 * <h2>告警怎么发</h2>
 * 这里只做"判定 + 留痕"：越过阈值时记 ERROR 并累加 {@code pms.outbox.backlog_breach} 计数，
 * 同时把 pending / 最老年龄暴露为 gauge 供抓取。真正的送达（Prometheus → Alertmanager →
 * 值班人）属 S2-06，本轮不假装已经能做——一个只写日志的"告警"如果被称为告警，
 * 会让人以为有人在看。
 *
 * <p>越界只在<b>状态翻转</b>时记一次 ERROR：每 30 秒重复同一条日志会把真正的新问题淹掉。
 * 计数则每轮都加，用于观察"积压持续了多久"（rate 即为持续时长）。
 */
@Component
@Conditional(WorkerRoleCondition.class)
@ConditionalOnProperty(name = "pms.outbox.monitor.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxBacklogMonitor {

    private static final Logger log = LoggerFactory.getLogger(OutboxBacklogMonitor.class);

    private final OutboxPublisherMapper mapper;
    private final long alertAgeSeconds;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final Counter breachCounter;

    /** 是否处于越界状态；只用于"翻转时记一次"，不参与判定。 */
    private volatile boolean breached;

    public OutboxBacklogMonitor(OutboxPublisherMapper mapper,
                                MeterRegistry meterRegistry,
                                @Value("${pms.outbox.alert-age:PT10M}") Duration alertAge) {
        if (alertAge.isNegative()) {
            throw new IllegalArgumentException("pms.outbox.alert-age 不能为负，收到 " + alertAge);
        }
        this.mapper = mapper;
        this.alertAgeSeconds = alertAge.toSeconds();
        Gauge.builder("pms.outbox.pending", pending, AtomicLong::get)
                .description("未发布事件条数（含退避等待中）")
                .register(meterRegistry);
        Gauge.builder("pms.outbox.oldest_event_age_seconds", oldestAgeSeconds, AtomicLong::get)
                .description("最老未发布事件的等待秒数；0 表示没有积压")
                .register(meterRegistry);
        this.breachCounter = Counter.builder("pms.outbox.backlog_breach")
                .description("积压越过阈值的刷新次数（rate 反映持续时长）")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${pms.outbox.monitor-interval:PT30S}",
            initialDelayString = "${pms.outbox.monitor-interval:PT30S}")
    public void refresh() {
        long count;
        Long oldest;
        try {
            count = mapper.countPending();
            oldest = mapper.oldestPendingSeconds();
        } catch (RuntimeException ex) {
            // 查不到就保持上次的值：把指标清零会让"积压中"看起来像"已恢复"
            log.warn("Outbox 积压读取失败，指标沿用上次值", ex);
            return;
        }

        pending.set(count);
        oldestAgeSeconds.set(oldest == null ? 0L : oldest);

        boolean nowBreached = oldest != null && oldest > alertAgeSeconds;
        if (nowBreached) {
            breachCounter.increment();
            if (!breached) {
                log.error("Outbox 积压告警：{} 条未发布，最老事件已等待 {} 秒（阈值 {} 秒）——"
                                + "检查 Broker 连通性、队列绑定与发布失败原因（outbox_event.last_error）",
                        count, oldest, alertAgeSeconds);
            }
        } else if (breached) {
            log.info("Outbox 积压已恢复：{} 条未发布，最老 {} 秒", count, oldest == null ? 0 : oldest);
        }
        breached = nowBreached;
    }

    /** 供测试与运维查询最近一次刷新值，避免为了看一眼数字去查库。 */
    public record Snapshot(long pending, long oldestAgeSeconds, boolean breached) {
    }

    public Snapshot snapshot() {
        return new Snapshot(pending.get(), oldestAgeSeconds.get(), breached);
    }
}
