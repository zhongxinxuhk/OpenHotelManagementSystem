package cn.sec.hn.openbackupsaas.shared.ratelimit;

/**
 * 限流存储抽象（J-05）。
 *
 * <p>当前只有进程内实现（{@link TokenBucketRateLimiter}）：ADR-011 把 Redis 列为暂缓，
 * 而用 PostgreSQL 每请求写一行计数，恰恰在最需要限流的时候（被打）给数据库加写压力。
 *
 * <p><b>接口存在的理由</b>：多实例部署时限额会被放大成 N 倍，那时需要换 Redis 实现。
 * 把契约先定下来，换实现时上层过滤器与规则表不动；
 * 同时也把"这套限额是单进程语义"这件事写在类型上，而不是藏在某个类的注释里。
 *
 * <p>实现必须**线程安全**，且键空间**有界**——键里含客户端 IP，
 * 攻击者随机造源地址就能让无界 Map 变成内存泄漏。
 */
public interface RateLimiter {

    /**
     * 尝试取用一个令牌。
     *
     * @param key     桶的标识（档位名 + 维度值，如 {@code guest.login|203.0.113.7}）
     * @param policy  档位
     * @return 判定结果；被拒时带建议重试间隔
     */
    RateLimitDecision tryAcquire(String key, RateLimitPolicy policy);

    /** 当前桶数量（运维与测试观测用）。 */
    int size();
}
