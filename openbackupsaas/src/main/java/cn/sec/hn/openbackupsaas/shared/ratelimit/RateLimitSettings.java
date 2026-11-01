package cn.sec.hn.openbackupsaas.shared.ratelimit;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 限流总开关与运行参数（J-05）。
 *
 * <p>三个参数刻意分开，因为它们回答的是不同的问题：
 * <ul>
 *   <li>{@code enabled}——出事故时能不能一条配置关掉限流（例如限额被误配成 1，
 *       全站在 429）。关掉要能立即生效，所以它不是一个"启动才读一次"的常量，
 *       而是跟着配置走。</li>
 *   <li>{@code trusted-proxies}——**安全参数**。它决定了"能不能读 X-Forwarded-For"，
 *       配错就等于让所有人自带无限额度（见 {@link ClientIpResolver}）。
 *       默认空集：谁都不信，只用 TCP 对端地址。</li>
 *   <li>{@code max-keys}——内存上限。见 {@link TokenBucketRateLimiter}。</li>
 * </ul>
 *
 * <p>默认开启。理由同 Outbox/Inbox：忘记开开关的代价是"匿名入口毫无防护"，
 * 而这类接口一旦被刷，付出的是短信费和数据库写入，不是日志里的一行 WARN。
 * 本地联调若被限额挡住，用 {@code PMS_RATE_LIMIT_ENABLED=false} 显式关。
 */
@Component
public class RateLimitSettings {

    private final boolean enabled;
    private final List<String> trustedProxies;
    private final int maxKeys;

    public RateLimitSettings(Environment environment) {
        this.enabled = environment.getProperty("pms.rate-limit.enabled", Boolean.class, Boolean.TRUE);
        this.trustedProxies = parseProxies(environment.getProperty("pms.rate-limit.trusted-proxies", ""));
        this.maxKeys = environment.getProperty("pms.rate-limit.max-keys", Integer.class, 50_000);
    }

    public boolean enabled() {
        return enabled;
    }

    public List<String> trustedProxies() {
        return trustedProxies;
    }

    public int maxKeys() {
        return maxKeys;
    }

    private static List<String> parseProxies(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
