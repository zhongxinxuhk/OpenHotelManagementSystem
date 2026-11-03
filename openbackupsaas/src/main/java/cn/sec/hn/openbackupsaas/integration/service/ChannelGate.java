package cn.sec.hn.openbackupsaas.integration.service;

import cn.sec.hn.openbackupsaas.integration.domain.TaskOutcome;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道门：限流 + 熔断（S2-03）。
 *
 * <p>两件事合在一处，因为它们回答的是同一个问题：<b>现在该不该打这个渠道</b>。
 *
 * <h2>限流</h2>
 * 按渠道维护"上次调用时间"。对方明确给了 QPS 时，超速只会换来 429 与更长的封禁，
 * 不如自己先等——等待成本是延迟，超速成本是被封。
 *
 * <h2>熔断</h2>
 * 连续失败到阈值就把渠道"关掉"一段时间：此时继续打只会把每次调用都变成一次超时等待，
 * 而任务本身在库里不会丢，冷却后照常重试。
 *
 * <h2>为什么是单机内存实现</h2>
 * 多实例部署时每个实例各自计数，实际总 QPS 会翻倍。这是<b>已知边界</b>：
 * 分布式限流需要共享状态（Redis/网关），而 Redis 按 ADR-011 首期不引入。
 * 因此这里的定位是"单实例自保 + 减少无谓调用"，不是精确配额。
 * 真正需要精确配额时，应在网关层做（S6 部署阶段）。
 */
@Component
public class ChannelGate {

    private final Map<String, ChannelState> states = new ConcurrentHashMap<>();

    /** 是否放行；不放行时给出"什么时候可以再来"。 */
    public Decision admit(String channel, ChannelPolicy policy, Instant now) {
        ChannelState state = states.computeIfAbsent(channel, key -> new ChannelState());
        synchronized (state) {
            if (state.openUntil != null && now.isBefore(state.openUntil)) {
                return new Decision(false, state.openUntil, "渠道熔断中，冷却至 " + state.openUntil);
            }
            if (state.openUntil != null) {
                // 冷却结束：重置，给渠道一次机会（半开状态）
                state.openUntil = null;
                state.consecutiveFailures = 0;
            }
            if (state.lastCallAt != null) {
                Instant earliest = state.lastCallAt.plus(policy.minInterval());
                if (now.isBefore(earliest)) {
                    return new Decision(false, earliest, "渠道限流：最小间隔 " + policy.minInterval());
                }
            }
            state.lastCallAt = now;
            return new Decision(true, null, null);
        }
    }

    /** 记录一次真实调用的结果，维护连续失败计数与熔断窗口。 */
    public void recordOutcome(String channel, ChannelPolicy policy, TaskOutcome outcome, Instant now) {
        ChannelState state = states.computeIfAbsent(channel, key -> new ChannelState());
        synchronized (state) {
            if (outcome.kind() == TaskOutcome.Kind.SUCCESS) {
                state.consecutiveFailures = 0;
                state.openUntil = null;
                return;
            }
            state.consecutiveFailures++;
            if (state.consecutiveFailures >= policy.circuitThreshold()) {
                state.openUntil = now.plus(policy.circuitCooldown());
            }
        }
    }

    /** 测试与运维观察用。 */
    public boolean isOpen(String channel, Instant now) {
        ChannelState state = states.get(channel);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.openUntil != null && now.isBefore(state.openUntil);
        }
    }

    public void reset(String channel) {
        states.remove(channel);
    }

    private static final class ChannelState {
        private Instant lastCallAt;
        private int consecutiveFailures;
        private Instant openUntil;
    }

    /**
     * @param allowed    是否放行
     * @param retryAfter 不放行时最早可重试时间
     * @param reason     不放行的原因（会写进任务的 last_error，便于排查"为什么没跑"）
     */
    public record Decision(boolean allowed, Instant retryAfter, String reason) {

        public Duration waitAtLeast(Instant now) {
            if (allowed || retryAfter == null) {
                return Duration.ZERO;
            }
            Duration wait = Duration.between(now, retryAfter);
            return wait.isNegative() ? Duration.ZERO : wait;
        }
    }
}
