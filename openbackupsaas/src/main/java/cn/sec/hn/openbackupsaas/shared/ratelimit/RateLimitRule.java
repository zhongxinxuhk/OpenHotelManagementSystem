package cn.sec.hn.openbackupsaas.shared.ratelimit;

import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

/**
 * 规则 = 匹配条件（方法 + 路径）+ 档位。
 *
 * <p><b>顺序即优先级</b>：注册表按声明顺序逐条匹配，第一条命中即返回。
 * 因此 {@code /api/v1/guest/auth/login} 这条必须排在 {@code /api/v1/guest/auth/**} 之前——
 * 反过来的话，登录接口永远只会命中那个宽松的大范围档位，
 * 而"登录单独定档"这件事在配置里看起来完全正确，实际一点没生效
 * （限流规则漏配不会报错，只会安静地不起作用，这是它最难发现的地方）。
 *
 * <p>{@code method} 为 {@code null} 表示不限方法。
 */
public record RateLimitRule(HttpMethod method, String pathPattern, RateLimitPolicy policy) {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    public boolean matches(String requestMethod, String path) {
        if (method != null && !method.matches(requestMethod)) {
            return false;
        }
        return MATCHER.match(pathPattern, path);
    }
}
