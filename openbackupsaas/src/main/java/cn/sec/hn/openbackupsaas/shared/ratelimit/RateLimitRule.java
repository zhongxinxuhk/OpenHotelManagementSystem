package cn.sec.hn.openbackupsaas.shared.ratelimit;

import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 规则 = 匹配条件（方法 + 路径）+ 档位 + **桶键维度**。
 *
 * <p><b>顺序即优先级</b>：注册表按声明顺序逐条匹配，第一条命中即返回。
 * 因此 {@code /api/v1/guest/auth/login} 这条必须排在 {@code /api/v1/guest/auth/**} 之前——
 * 反过来的话，登录接口永远只会命中那个宽松的大范围档位，
 * 而"登录单独定档"这件事在配置里看起来完全正确，实际一点没生效
 * （限流规则漏配不会报错，只会安静地不起作用，这是它最难发现的地方）。
 *
 * <p>{@code method} 为 {@code null} 表示不限方法。
 *
 * <h2>桶键维度（R-20261103-02）</h2>
 * <ul>
 *   <li>{@link KeyDimension#CLIENT}（默认）：桶键 = 档位名 + 客户端 IP——
 *       防单源滥用，这是 J-05 的既有行为；</li>
 *   <li>{@link KeyDimension#PATH_VARIABLES}：桶键 = 档位名 + 路径模板变量的值，
 *       **不看 IP**——防分布式换源。首个用例是公开面的按门店配额
 *       （{@code public.catalog.hotel}，键 = tenantCode/hotelCode）：
 *       攻击者轮换 IP 也逃不出单店的店级额度，而正常的多客源流量
 *       （额度高于单 IP 档位）不受影响。</li>
 * </ul>
 */
public record RateLimitRule(HttpMethod method, String pathPattern, RateLimitPolicy policy,
                            KeyDimension dimension) {

    /** 桶键除档位名之外的维度来源。 */
    public enum KeyDimension {
        /** 档位名 + 客户端 IP（防单源滥用）。 */
        CLIENT,
        /** 档位名 + 路径模板变量的值（防分布式换源，如按门店配额）。 */
        PATH_VARIABLES
    }

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 兼容既有调用：客户端维度。 */
    public RateLimitRule(HttpMethod method, String pathPattern, RateLimitPolicy policy) {
        this(method, pathPattern, policy, KeyDimension.CLIENT);
    }

    public boolean matches(String requestMethod, String path) {
        if (method != null && !method.matches(requestMethod)) {
            return false;
        }
        return MATCHER.match(pathPattern, path);
    }

    /**
     * 路径维度的桶键片段：按变量名排序拼接变量的值
     * （{@code /api/v1/public/{tenantCode}/hotels/{hotelCode}/room-types} 匹配后
     * 得到 {@code "pms-admin/hq"}）。
     *
     * <p>排序是为了确定性：{@code extractUriTemplateVariables} 返回的 Map
     * 无序，同一规则对同一路径必须得到同一个键，否则桶会被随机打散。
     * 变量值来自 URL 路径段，只可能是正常字符（编码后的分段）；
     * 键空间总量仍受 RateLimiter 的 maxKeys 上界约束。
     */
    public String scopeKey(String path) {
        Map<String, String> variables = MATCHER.extractUriTemplateVariables(pathPattern, path);
        return variables.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .collect(Collectors.joining("/"));
    }
}
