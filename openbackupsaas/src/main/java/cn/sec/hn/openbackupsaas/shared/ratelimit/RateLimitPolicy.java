package cn.sec.hn.openbackupsaas.shared.ratelimit;

import java.time.Duration;

/**
 * 一条限流档位：在 {@code window} 时间窗内允许 {@code permits} 次，允许瞬时用满（令牌桶容量 = permits）。
 *
 * <p><b>为什么是"桶"而不是"固定窗口计数"</b>：固定窗口在边界处会放过 2 倍流量——
 * 第 59 秒打满 10 次、第 61 秒再打满 10 次，中间只隔 2 秒。对登录接口来说，
 * 那就是把 10 次/分钟的暴力破解变成 10 次/两秒。令牌桶的持续速率恒为
 * {@code permits/window}，突发上限也是 {@code permits}，两个数都可解释。
 *
 * <p>{@code name} 同时是**指标标签**与配置覆盖的键，必须低基数（小写 + 点）。
 */
public record RateLimitPolicy(String name, int permits, Duration window) {

    public RateLimitPolicy {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("限流档位必须有名字");
        }
        if (permits <= 0) {
            throw new IllegalArgumentException("限流 permits 必须为正数：" + name);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("限流窗口必须为正时长：" + name);
        }
    }

    /** 平均每秒允许多少次——令牌桶的补充速率。 */
    public double permitsPerNano() {
        return (double) permits / window.toNanos();
    }

    /** 供启动日志与运维接口读取的一行描述。 */
    public String describe() {
        return name + "=" + permits + "/" + window;
    }
}
