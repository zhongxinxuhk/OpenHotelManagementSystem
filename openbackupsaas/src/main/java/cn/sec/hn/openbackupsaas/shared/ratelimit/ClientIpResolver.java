package cn.sec.hn.openbackupsaas.shared.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析"这个请求到底来自谁"（J-05）。
 *
 * <h2>默认只信 TCP 对端</h2>
 * {@code X-Forwarded-For} 是客户端可以随便写的头。若无条件采信它，
 * 攻击者每次请求换一个假地址就得到无限额度——**限流等于没做**，
 * 而且从日志上看每一"个"来源都只请求了一两次，事后排查还会得出"没被打"的结论。
 *
 * <p>因此规则是：只有 TCP 对端本身命中 {@code pms.rate-limit.trusted-proxies}（本机反代 /
 * 负载均衡）时才读这个头；读的时候**从右往左**走——最右侧是离服务端最近的一跳，
 * 左侧是客户端可伪造的部分。跳过可信代理，第一个不可信地址才是真正的客户端。
 *
 * <h2>规范化</h2>
 * 同一地址的不同写法（{@code ::1} / {@code 0:0:0:0:0:0:0:1}）必须归一到同一个键，
 * 否则改个写法就换一个新桶。
 */
public final class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    /** 最多看几跳：防止有人塞一个超长 XFF 让我们白算。 */
    private static final int MAX_HOPS = 20;

    private static final String UNKNOWN = "unknown";

    private final List<IpRange> trustedProxies;

    public ClientIpResolver(List<String> trustedProxies) {
        List<IpRange> parsed = new ArrayList<>();
        for (String raw : trustedProxies) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            // 配置错误在启动期暴露：写错了就起不来，而不是安静地退化成"谁都不信"
            parsed.add(IpRange.parse(raw));
        }
        this.trustedProxies = List.copyOf(parsed);
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        InetAddress remoteAddress = tryParse(remote);
        if (remoteAddress == null) {
            return UNKNOWN;
        }
        if (!isTrustedProxy(remoteAddress)) {
            // 不可信对端：忽略一切转发头，避免被伪造
            return canonical(remoteAddress);
        }
        List<String> hops = forwardedHops(request);
        for (int i = hops.size() - 1; i >= 0; i--) {
            InetAddress hop = tryParse(hops.get(i));
            if (hop == null) {
                // 该跳不是 IP 字面量（如 "unknown"）：无法判断，停在这里用已知的最后一跳
                break;
            }
            if (!isTrustedProxy(hop)) {
                return canonical(hop);
            }
        }
        // 全是可信代理（常见于多层反代）：用最左侧一跳；没有则用对端
        InetAddress leftmost = hops.isEmpty() ? null : tryParse(hops.get(0));
        return canonical(leftmost == null ? remoteAddress : leftmost);
    }

    /** 是否把该地址视为可信代理（供诊断输出使用）。 */
    public boolean isTrustedProxy(InetAddress address) {
        for (IpRange range : trustedProxies) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasTrustedProxies() {
        return !trustedProxies.isEmpty();
    }

    private static List<String> forwardedHops(HttpServletRequest request) {
        String header = request.getHeader("X-Forwarded-For");
        if (header == null || header.isBlank()) {
            return List.of();
        }
        List<String> hops = new ArrayList<>(4);
        for (String part : header.split(",")) {
            String trimmed = stripPort(part.trim());
            if (!trimmed.isEmpty()) {
                hops.add(trimmed);
            }
            if (hops.size() >= MAX_HOPS) {
                break;
            }
        }
        return hops;
    }

    /**
     * 去掉 {@code [::1]:443} 或 {@code 203.0.113.7:1234} 里的端口。
     * 只处理这两种明确形态，其它一律原样返回（原样进 {@link #tryParse} 会返回 null 而被忽略）。
     */
    private static String stripPort(String value) {
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            return close > 0 ? value.substring(1, close) : value;
        }
        int firstColon = value.indexOf(':');
        boolean singleColon = firstColon == value.lastIndexOf(':');
        if (singleColon && firstColon > 0) {
            return value.substring(0, firstColon);
        }
        return value;
    }

    private static InetAddress tryParse(String value) {
        try {
            return IpRange.literal(value);
        } catch (IllegalArgumentException ex) {
            // 不记 WARN：这条路会被攻击者主动喂垃圾头触发，记日志等于给自己制造日志洪水
            log.debug("忽略无法解析的来源地址：{}", value);
            return null;
        }
    }

    private static String canonical(InetAddress address) {
        return address.getHostAddress();
    }
}
