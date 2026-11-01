package cn.sec.hn.openbackupsaas.shared.ratelimit;

/**
 * 一次限流判定结果。
 *
 * @param allowed      是否放行
 * @param limit        该档位的窗口额度（用于 {@code X-RateLimit-Limit}）
 * @param remaining    放行后的剩余额度（向下取整，仅供客户端平滑退避，不是承诺）
 * @param retryAfterSeconds 被拒时建议的重试间隔（{@code Retry-After}）；放行时为 0
 */
public record RateLimitDecision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {

    public static RateLimitDecision allow(int limit, int remaining) {
        return new RateLimitDecision(true, limit, Math.max(remaining, 0), 0);
    }

    public static RateLimitDecision reject(int limit, long retryAfterSeconds) {
        // 至少 1 秒：Retry-After: 0 会让某些客户端原地重试，等于自己放大攻击面
        return new RateLimitDecision(false, limit, 0, Math.max(retryAfterSeconds, 1));
    }
}
