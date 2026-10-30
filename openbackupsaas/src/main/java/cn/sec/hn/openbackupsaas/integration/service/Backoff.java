package cn.sec.hn.openbackupsaas.integration.service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * 指数退避 + 抖动（S2-03）。
 *
 * <h2>为什么必须有抖动</h2>
 * 没有抖动时，同一批任务会在同一时刻集体重试：对方刚恢复就被自己的重试洪峰再打挂一次，
 * 然后进入"恢复—打挂"的循环。抖动把重试摊开，代价只是最坏情况多等一点。
 *
 * <p>抖动范围取 ±20%（0.8–1.2 倍）。范围太大会让退避失去意义，太小则无法错峰。
 *
 * <p>{@code jitter} 可注入是为了测试：断言"落在区间内"而不是断言某个具体秒数，
 * 否则测试会变成对随机数实现的快照。
 */
public final class Backoff {

    private final Duration base;
    private final Duration max;
    private final DoubleSupplier jitter;

    public Backoff(Duration base, Duration max) {
        this(base, max, () -> 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4);
    }

    public Backoff(Duration base, Duration max, DoubleSupplier jitter) {
        if (base.isZero() || base.isNegative()) {
            throw new IllegalArgumentException("退避基数必须为正：" + base);
        }
        if (max.compareTo(base) < 0) {
            throw new IllegalArgumentException("退避上限不能小于基数：" + max + " < " + base);
        }
        this.base = base;
        this.max = max;
        this.jitter = jitter;
    }

    /**
     * 第 {@code attempt} 次尝试失败后的等待时长（attempt 从 1 开始）。
     *
     * <p>先算指数增长再截断，最后加抖动：反过来（先抖动再截断）会让上限附近失去随机性。
     */
    public Duration delayFor(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 20));
        long baseSeconds = base.toSeconds() * (1L << exponent);
        long cappedSeconds = Math.min(baseSeconds, max.toSeconds());
        double factor = jitter.getAsDouble();
        long withJitter = Math.round(cappedSeconds * factor);
        return Duration.ofSeconds(Math.max(1, withJitter));
    }
}
