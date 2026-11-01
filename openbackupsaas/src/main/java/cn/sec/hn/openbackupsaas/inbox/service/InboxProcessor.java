package cn.sec.hn.openbackupsaas.inbox.service;

import cn.sec.hn.openbackupsaas.inbox.domain.InboxMessage;
import cn.sec.hn.openbackupsaas.inbox.domain.InboxOutcome;
import cn.sec.hn.openbackupsaas.inbox.domain.InboxPosition;
import cn.sec.hn.openbackupsaas.inbox.mapper.InboxMapper;
import cn.sec.hn.openbackupsaas.shared.observability.PmsMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbox 消费的核心流程（S2-02）：去重 → 版本判定 → 应用 → 推进位点，全程一个事务。
 *
 * <h2>为什么必须在同一个事务里</h2>
 * 三件事必须同生共死：
 * <ol>
 *   <li>认领（写 Inbox 台账）；</li>
 *   <li>业务变更（{@link InboxConsumer#apply}）；</li>
 *   <li>位点推进。</li>
 * </ol>
 * 任何一步单独提交都会留下错误的中间态：只提交认领 → 消息被标记处理过但业务没发生（永久丢失）；
 * 只提交业务 → 重投时再执行一次（重复）；只提交位点 → 旧事件被当成新事件。
 *
 * <h2>顺序：先认领，后应用</h2>
 * 认领必须写在应用<b>之前</b>：并发处理同一事件时，唯一键让"后到者"拿到 0 行并直接退出，
 * 只有一个人会执行应用。反过来（先应用再认领）两个并发消费者会各应用一次。
 *
 * <h2>版本规则</h2>
 * <table border="1">
 *   <caption>按位点判定的四种结论</caption>
 *   <tr><th>条件</th><th>结论</th><th>理由</th></tr>
 *   <tr><td>未订阅该类型</td><td>IGNORED</td><td>事件总线比消费者新，不是错误，但要留痕</td></tr>
 *   <tr><td>(版本,序号) ≤ 位点</td><td>SKIPPED_STALE</td><td>旧事件，应用它会用旧状态覆盖新状态</td></tr>
 *   <tr><td>版本 &gt; 位点 + 1</td><td>PARKED_GAP</td><td>中间那版丢了，先挂起等补齐，不能猜着应用</td></tr>
 *   <tr><td>其余</td><td>PROCESSED</td><td>顺序正确，正常应用</td></tr>
 * </table>
 *
 * <h2>首次见到某个聚合：接管当前版本，而不是判成缺口</h2>
 * 新消费者上线时，存量聚合的版本早已不是 1，而它们的历史事件从来没有被这个消费者处理过
 * （总线上没有）。若把"版本 3"当作缺口挂起，这个消费者会永久卡在存量数据上，
 * 等到 v1 重投——那一天不会来。
 *
 * 因此<b>没有位点时以收到的版本为起点</b>：这是唯一一次"不按顺序"的接受，
 * 之后仍严格按位点判定。这条规则必须显式写下来，否则下一个人会把它当成 bug 改掉。
 */
@Service
public class InboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboxProcessor.class);

    private final InboxMapper mapper;
    private final PmsMetrics metrics;

    public InboxProcessor(InboxMapper mapper, PmsMetrics metrics) {
        this.mapper = mapper;
        this.metrics = metrics;
    }

    /**
     * 处理一条新到达的消息。
     *
     * <p>重复投递返回 {@code duplicate = true}，并把<b>第一次</b>的结论带回去——
     * 只说"跳过了"会让排查者无法区分"重复"和"被当成旧事件"。
     */
    @Transactional
    public InboxResult process(InboxConsumer consumer, InboxMessage message) {
        InboxPosition position = positionOf(consumer, message);
        long lastVersion = position == null ? 0L : position.versionOrZero();
        int lastOrdinal = position == null ? 0 : position.ordinalOrZero();

        InboxOutcome decision = decide(consumer, message, position);
        String detail = describe(decision, message, lastVersion, lastOrdinal);

        int claimed = mapper.recordIfAbsent(consumer.name(), message, decision, detail);
        if (claimed == 0) {
            String existing = mapper.findOutcome(consumer.name(), message.eventId());
            InboxOutcome first = existing == null ? decision : InboxOutcome.valueOf(existing);
            // 重复投递是 at-least-once 的正常副产物，单独记一条，便于发现"重投风暴"。
            metrics.inbox("duplicate");
            log.debug("事件 {} 已由消费者 {} 处理过（结论 {}），本次跳过",
                    message.eventId(), consumer.name(), first);
            return new InboxResult(first, true);
        }

        metrics.inbox(decision.name().toLowerCase());
        if (decision == InboxOutcome.PROCESSED) {
            consumer.apply(message);
            mapper.advancePosition(consumer.name(), message.tenantId(), message.aggregateType(),
                    message.aggregateId(), message.aggregateVersion(), message.eventOrdinal());
        } else if (decision == InboxOutcome.PARKED_GAP) {
            log.warn("事件 {}（{} v{} 序号{}）遇到版本缺口：位点 v{}.{}，已挂起等待补齐；"
                            + "补齐后可用 InboxIntegrityService 重放",
                    message.eventId(), message.aggregateType(), message.aggregateVersion(),
                    message.eventOrdinal(), lastVersion, lastOrdinal);
        }
        return new InboxResult(decision, false);
    }

    /**
     * 重放一条此前被挂起的事件（缺口补齐之后）。
     *
     * <p>重新判定而不是"直接应用"：缺口可能仍未补齐（仍挂起），或者期间位点已被别的事件推过（成旧事件）。
     * 重放路径上唯一多出来的一步是把已有的挂起行改写结论——台账行本身不重插，
     * 否则唯一键会把它当成重复。
     */
    @Transactional
    public InboxResult redrive(InboxConsumer consumer, InboxMessage parked) {
        InboxPosition position = positionOf(consumer, parked);
        long lastVersion = position == null ? 0L : position.versionOrZero();
        int lastOrdinal = position == null ? 0 : position.ordinalOrZero();

        InboxOutcome decision = decide(consumer, parked, position);
        if (decision == InboxOutcome.PARKED_GAP) {
            return new InboxResult(InboxOutcome.PARKED_GAP, false);
        }

        metrics.inbox(decision.name().toLowerCase());
        String detail = describe(decision, parked, lastVersion, lastOrdinal);
        if (decision == InboxOutcome.PROCESSED) {
            consumer.apply(parked);
            mapper.advancePosition(consumer.name(), parked.tenantId(), parked.aggregateType(),
                    parked.aggregateId(), parked.aggregateVersion(), parked.eventOrdinal());
        }
        mapper.settleParked(consumer.name(), parked.eventId(), decision, detail);
        return new InboxResult(decision, false);
    }

    private InboxPosition positionOf(InboxConsumer consumer, InboxMessage message) {
        return mapper.findPosition(consumer.name(), message.tenantId(), message.aggregateType(),
                message.aggregateId());
    }

    private InboxOutcome decide(InboxConsumer consumer, InboxMessage message, InboxPosition position) {
        if (!consumer.supports(message.eventType())) {
            return InboxOutcome.IGNORED;
        }
        if (position == null) {
            // 首次见到该聚合：接管当前版本（见类注释"首次见到某个聚合"）。
            return InboxOutcome.PROCESSED;
        }
        long lastVersion = position.versionOrZero();
        int lastOrdinal = position.ordinalOrZero();
        if (!message.isNewerThan(lastVersion, lastOrdinal)) {
            return InboxOutcome.SKIPPED_STALE;
        }
        if (message.isGapAfter(lastVersion)) {
            return InboxOutcome.PARKED_GAP;
        }
        return InboxOutcome.PROCESSED;
    }

    private String describe(InboxOutcome outcome, InboxMessage message, long lastVersion, int lastOrdinal) {
        return switch (outcome) {
            case IGNORED -> "未订阅的事件类型：" + message.eventType();
            case SKIPPED_STALE -> "旧事件：位点 v" + lastVersion + "." + lastOrdinal
                    + "，本条 v" + message.aggregateVersion() + "." + message.eventOrdinal();
            case PARKED_GAP -> "版本缺口：期望 v" + (lastVersion + 1)
                    + "，收到 v" + message.aggregateVersion();
            case PROCESSED -> "已应用 v" + message.aggregateVersion() + "." + message.eventOrdinal();
        };
    }

    /**
     * 一次处理的结果。
     *
     * @param outcome   结论（重复时是第一次的结论）
     * @param duplicate 是否因为"已处理过"而跳过
     */
    public record InboxResult(InboxOutcome outcome, boolean duplicate) {
    }
}
