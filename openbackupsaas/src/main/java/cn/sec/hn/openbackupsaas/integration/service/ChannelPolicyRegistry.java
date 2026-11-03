package cn.sec.hn.openbackupsaas.integration.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按渠道解析调用策略（S2-03）。
 *
 * <p>用 {@link Environment} 直接取值而不是 {@code @ConfigurationProperties} 的 Map：
 * 渠道是运营期会新增的（每接一家 OTA 一个），用宽松绑定写 Map 容易在拼错键名时静默退化成默认值。
 * 这里显式取值 + 默认值，并在启动时打印实际生效的策略，让"我配了但没生效"无处可藏。
 *
 * <p>默认值的选择依据：OTA 的公开限流普遍在 1–5 QPS、单次超时 10 秒量级；
 * 熔断阈值取 5 次连续失败——太少会被一次网络抖动触发，太多等于没熔断。
 */
@Component
public class ChannelPolicyRegistry {

    static final int DEFAULT_MAX_ATTEMPTS = 5;
    static final Duration DEFAULT_MIN_INTERVAL = Duration.ZERO;
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    static final int DEFAULT_CIRCUIT_THRESHOLD = 5;
    static final Duration DEFAULT_CIRCUIT_COOLDOWN = Duration.ofSeconds(60);

    private final Environment environment;
    private final Map<String, ChannelPolicy> cache = new ConcurrentHashMap<>();

    public ChannelPolicyRegistry(Environment environment) {
        this.environment = environment;
    }

    public ChannelPolicy forChannel(String channel) {
        return cache.computeIfAbsent(channel, this::resolve);
    }

    private ChannelPolicy resolve(String channel) {
        String prefix = "pms.integration.channel." + channel + ".";
        return new ChannelPolicy(
                channel,
                intValue(prefix + "max-attempts", DEFAULT_MAX_ATTEMPTS),
                durationValue(prefix + "min-interval", DEFAULT_MIN_INTERVAL),
                durationValue(prefix + "timeout", DEFAULT_TIMEOUT),
                intValue(prefix + "circuit-threshold", DEFAULT_CIRCUIT_THRESHOLD),
                durationValue(prefix + "circuit-cooldown", DEFAULT_CIRCUIT_COOLDOWN));
    }

    private int intValue(String key, int fallback) {
        Integer value = environment.getProperty(key, Integer.class);
        return value == null ? fallback : value;
    }

    private Duration durationValue(String key, Duration fallback) {
        Duration value = environment.getProperty(key, Duration.class);
        return value == null ? fallback : value;
    }

    /** 供运维查当前生效参数（配置错了不用重启猜）。 */
    public Map<String, ChannelPolicy> snapshot() {
        return Map.copyOf(cache);
    }
}
