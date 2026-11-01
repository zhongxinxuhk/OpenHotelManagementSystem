package cn.sec.hn.openbackupsaas.shared.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 进程内令牌桶限流（J-05）。
 *
 * <h2>为什么是令牌桶</h2>
 * 见 {@link RateLimitPolicy}：固定窗口在边界能放过两倍流量，而登录接口最怕的正是这个。
 * 桶容量取"窗口额度"，所以对外可解释为「{@code window} 内最多 {@code permits} 次」。
 *
 * <h2>惰性补充，没有后台线程</h2>
 * 每次取用时按 {@code 经过时间 × 速率} 补令牌，没有定时器扫全表。
 * 后台线程方案在桶数量上来后是纯粹的 CPU 浪费，而且会让"这一秒到底补了多少"变得难以断言。
 *
 * <h2>键空间必须有界</h2>
 * 键含客户端地址。若可信代理配错（把不可信的来源网段当代理），
 * 攻击者每次伪造一个 {@code X-Forwarded-For} 就得到一个全新桶——无界 Map 直接变成内存泄漏，
 * 限流器自己成了拒绝服务的入口。所以这里做两层保护：
 * <ol>
 *   <li>桶满（= 已完全补充、状态为零）即视为空闲可丢，丢弃无信息损失，每 {@value #SWEEP_INTERVAL_SECONDS} 秒扫一次；</li>
 *   <li>扫完仍超上限，按迭代序强行淘汰到 90% 容量，并 WARN 一次。
 *       代价是这一小撮客户端被"重置"，而系统继续可用——比 OOM 好。</li>
 * </ol>
 *
 * <h2>已知边界（写进 ADR-020，不靠注释兜）</h2>
 * 计数不在进程间共享：N 个实例的实际限额是配置值的 N 倍。单实例部署（当前形态）无差异；
 * 横向扩容前必须换成 Redis 实现，或把限额按实例数均摊。
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    private static final long SWEEP_INTERVAL_SECONDS = 5;

    private final int maxKeys;
    private final LongSupplier nanoClock;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** 下次允许扫描的时刻；{@link Long#MIN_VALUE} 表示还没扫过（nanoTime 的原点可以是负数，不能用 0 当哨兵）。 */
    private long sweepDeadline = Long.MIN_VALUE;

    /** 上一次"被迫淘汰活跃桶"的告警时刻，用于限流日志本身（见 {@link #warnEviction}）。 */
    private long lastEvictionWarnAt = Long.MIN_VALUE;

    public TokenBucketRateLimiter(int maxKeys) {
        this(maxKeys, System::nanoTime);
    }

    /** 供测试注入可控时钟，避免用 sleep 制造时序。 */
    public TokenBucketRateLimiter(int maxKeys, LongSupplier nanoClock) {
        if (maxKeys <= 0) {
            throw new IllegalArgumentException("maxKeys 必须为正数");
        }
        this.maxKeys = maxKeys;
        this.nanoClock = nanoClock;
    }

    @Override
    public RateLimitDecision tryAcquire(String key, RateLimitPolicy policy) {
        long now = nanoClock.getAsLong();
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(policy.permits(), now));
        }
        RateLimitDecision decision = bucket.acquire(policy, now);
        if (buckets.size() > maxKeys) {
            enforceCapacity(now, policy.permits());
        }
        return decision;
    }

    @Override
    public int size() {
        return buckets.size();
    }

    /**
     * 键空间护栏。
     *
     * <p>先做**无损**清理：桶满意味着它已经回到初始状态，丢掉不损失任何计数。
     * 这一步按 {@value #SWEEP_INTERVAL_SECONDS} 秒的节奏做，因为全表扫描本身有成本，
     * 而正常流量下键空间根本不会逼近上限。
     *
     * <p>扫完仍超限，说明确实在被"换着地址打"（或者可信代理配错了），
     * 此时按迭代序淘汰活跃桶到 90% 容量。代价是一小撮客户端被重置额度——
     * 这是有意选的：另一种结局是 OOM，服务对所有人为不可用。
     */
    private synchronized void enforceCapacity(long now, int permits) {
        boolean sweepAllowed = sweepDeadline == Long.MIN_VALUE || now >= sweepDeadline;
        if (sweepAllowed) {
            sweepDeadline = now + Duration.ofSeconds(SWEEP_INTERVAL_SECONDS).toNanos();
            buckets.entrySet().removeIf(entry -> entry.getValue().isFull(permits));
        }
        if (buckets.size() <= maxKeys) {
            return;
        }

        int before = buckets.size();
        int target = maxKeys * 9 / 10;
        int excess = before - target;
        int removed = 0;
        var iterator = buckets.entrySet().iterator();
        while (iterator.hasNext() && removed < excess) {
            iterator.next();
            iterator.remove();
            removed++;
        }
        warnEviction(before, removed, permits);
    }

    /** 被刷时会持续越限，日志不能每次越限都写一行——那样它自己就是新的洪水。 */
    private void warnEviction(int before, int removed, int permits) {
        long now = nanoClock.getAsLong();
        if (lastEvictionWarnAt != Long.MIN_VALUE
                && now - lastEvictionWarnAt < Duration.ofSeconds(60).toNanos()) {
            return;
        }
        lastEvictionWarnAt = now;
        log.warn("限流键空间触及上限 {}，丢弃 {} 个活跃桶（{} → {}，permits={}）。"
                        + "若持续出现，检查 pms.rate-limit.trusted-proxies 是否把不可信来源当成了代理",
                maxKeys, removed, before, buckets.size(), permits);
    }

    /** 单个桶。按 key 互斥即可，不必全局锁——不同 key 之间没有共享状态。 */
    private static final class Bucket {

        private double tokens;
        private long lastNanos;

        Bucket(int permits, long now) {
            this.tokens = permits;
            this.lastNanos = now;
        }

        synchronized RateLimitDecision acquire(RateLimitPolicy policy, long now) {
            int permits = policy.permits();
            // 档位被调小时（重启改配置），旧桶不能仍然持有更大的容量
            if (tokens > permits) {
                tokens = permits;
            }
            long elapsed = now - lastNanos;
            if (elapsed > 0) {
                double refill = elapsed * policy.permitsPerNano();
                if (refill > 0) {
                    tokens = Math.min(permits, tokens + refill);
                    lastNanos = now;
                }
            } else if (elapsed < 0) {
                // nanoTime 单调，正常不会回退；换实现/注入时钟时才可能，兜一下
                lastNanos = now;
            }

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return RateLimitDecision.allow(permits, (int) Math.floor(tokens));
            }

            // 还差多少纳秒才能攒够一个令牌
            long waitNanos = (long) Math.ceil((1.0 - tokens) / policy.permitsPerNano());
            long waitSeconds = (waitNanos + 999_999_999L) / 1_000_000_000L;
            return RateLimitDecision.reject(permits, waitSeconds);
        }

        synchronized boolean isFull(int permits) {
            return tokens >= permits;
        }
    }
}
