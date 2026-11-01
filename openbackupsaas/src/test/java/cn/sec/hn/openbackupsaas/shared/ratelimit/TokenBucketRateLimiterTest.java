package cn.sec.hn.openbackupsaas.shared.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌桶的边界行为（J-05）。
 *
 * <p>不用 {@code Thread.sleep}：时钟是注入的。用 sleep 写的限流测试，
 * 一旦 CI 机器慢一点就会红，而"慢机器上红"的测试最后都会被调宽到失去意义。
 */
class TokenBucketRateLimiterTest {

    private static final RateLimitPolicy FIVE_PER_MINUTE =
            new RateLimitPolicy("test.rule", 5, Duration.ofMinutes(1));

    private final AtomicLong now = new AtomicLong(1_000_000);

    private TokenBucketRateLimiter limiter(int maxKeys) {
        return new TokenBucketRateLimiter(maxKeys, now::get);
    }

    @Test
    void allowsExactlyTheBudgetThenRejects() {
        TokenBucketRateLimiter limiter = limiter(1000);

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("k", FIVE_PER_MINUTE).allowed(), "第 " + (i + 1) + " 次应放行");
        }

        RateLimitDecision sixth = limiter.tryAcquire("k", FIVE_PER_MINUTE);
        assertFalse(sixth.allowed(), "额度用尽后必须拒绝");
        assertEquals(5, sixth.limit());
        // 5 次/分钟 → 补一个令牌要 12 秒
        assertEquals(12, sixth.retryAfterSeconds(), "Retry-After 应等于攒够一个令牌的时间");
    }

    @Test
    void refillsContinuouslyInsteadOfPerWindowBoundary() {
        TokenBucketRateLimiter limiter = limiter(1000);
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("k", FIVE_PER_MINUTE);
        }
        assertFalse(limiter.tryAcquire("k", FIVE_PER_MINUTE).allowed());

        // 半分钟 = 半个窗口 = 补 2.5 个令牌：应当重新允许 2 次
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        assertTrue(limiter.tryAcquire("k", FIVE_PER_MINUTE).allowed());
        assertTrue(limiter.tryAcquire("k", FIVE_PER_MINUTE).allowed());
        // 第 3 次只剩 0.5 个令牌，不够
        assertFalse(limiter.tryAcquire("k", FIVE_PER_MINUTE).allowed());
    }

    @Test
    void neverExceedsCapacityAfterLongIdle() {
        TokenBucketRateLimiter limiter = limiter(1000);
        limiter.tryAcquire("k", FIVE_PER_MINUTE);

        now.addAndGet(Duration.ofHours(2).toNanos());

        // 空闲再久也不会攒出超过容量的额度：一波流只放 5 次
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("k", FIVE_PER_MINUTE).allowed());
        }
        assertFalse(limiter.tryAcquire("k", FIVE_PER_MINUTE).allowed());
    }

    @Test
    void differentKeysDoNotShareBudget() {
        TokenBucketRateLimiter limiter = limiter(1000);
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("a", FIVE_PER_MINUTE);
        }
        assertFalse(limiter.tryAcquire("a", FIVE_PER_MINUTE).allowed());
        assertTrue(limiter.tryAcquire("b", FIVE_PER_MINUTE).allowed(), "换一个键必须重新有满额度");
    }

    @Test
    void retryAfterIsNeverZero() {
        // 极小额度 + 长窗口：剩余等待可能不足 1 秒，但 Retry-After: 0 会让客户端原地重试
        RateLimitPolicy slow = new RateLimitPolicy("slow.rule", 1, Duration.ofHours(1));
        TokenBucketRateLimiter limiter = limiter(1000);

        assertTrue(limiter.tryAcquire("k", slow).allowed());
        assertTrue(limiter.tryAcquire("k", slow).retryAfterSeconds() >= 1);
    }

    @Test
    void keySpaceStaysBoundedUnderKeyFlood() {
        // 模拟"每个请求换一个伪造来源"：键空间必须有界，否则限流器自己变成内存泄漏
        TokenBucketRateLimiter limiter = limiter(50);

        for (int i = 0; i < 500; i++) {
            now.addAndGet(Duration.ofSeconds(6).toNanos());
            limiter.tryAcquire("key-" + i, FIVE_PER_MINUTE);
        }

        assertTrue(limiter.size() <= 50, "桶数量必须被 max-keys 约束，实际 " + limiter.size());
    }

    @Test
    void shrinkingPolicyCapacityIsRespected() {
        TokenBucketRateLimiter limiter = limiter(1000);
        RateLimitPolicy generous = new RateLimitPolicy("test.rule", 10, Duration.ofMinutes(1));
        RateLimitPolicy strict = new RateLimitPolicy("test.rule", 2, Duration.ofMinutes(1));

        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k", generous);
        }

        // 改了配置（重启后额度调小）：旧桶不能仍然握着 10 个令牌
        for (int i = 0; i < 2; i++) {
            assertTrue(limiter.tryAcquire("k", strict).allowed());
        }
        assertFalse(limiter.tryAcquire("k", strict).allowed());
    }
}
