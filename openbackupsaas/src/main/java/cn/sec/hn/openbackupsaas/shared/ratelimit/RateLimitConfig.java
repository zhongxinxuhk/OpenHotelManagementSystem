package cn.sec.hn.openbackupsaas.shared.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 限流组件的装配（J-05）。
 *
 * <p>显式声明而不是把实现类标 {@code @Component}：实现类没有依赖注入需求，
 * 让它自己变成 Bean 会让"换 Redis 实现"必须改实现类的注解而不是改这里的装配。
 */
@Configuration
public class RateLimitConfig {

    @Bean
    RateLimiter rateLimiter(RateLimitSettings settings) {
        return new TokenBucketRateLimiter(settings.maxKeys());
    }

    @Bean
    ClientIpResolver clientIpResolver(RateLimitSettings settings) {
        return new ClientIpResolver(settings.trustedProxies());
    }
}
