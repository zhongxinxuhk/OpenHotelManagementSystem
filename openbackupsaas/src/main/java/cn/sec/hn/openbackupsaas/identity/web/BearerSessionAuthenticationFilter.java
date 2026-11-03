package cn.sec.hn.openbackupsaas.identity.web;

import cn.sec.hn.openbackupsaas.identity.service.AuthenticationService;
import cn.sec.hn.openbackupsaas.shared.security.PmsPrincipal;
import cn.sec.hn.openbackupsaas.shared.security.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Bearer 会话认证过滤器（S1-03）。
 *
 * <p>行为约定：
 * <ul>
 *   <li>没有 {@code Authorization: Bearer} 头 → 不放行身份，交给安全链按未认证处理（401）。</li>
 *   <li>令牌无效/已撤销/已过期/账号或租户被停用 → 同样当未认证，**不返回具体原因**，
 *       否则失效原因的差异就能被用来探测会话是否存在。</li>
 *   <li>认证成功后同时绑定 Spring Security 上下文与 {@link TenantContextHolder}；
 *       请求结束**必须**清理 ThreadLocal，否则线程复用会把上一个请求的租户带出去。</li>
 * </ul>
 *
 * <p>本类故意**不加** {@code @Component}：Boot 会把容器里的每个 {@code Filter} Bean
 * 自动注册成 Servlet Filter，同时又把它插进安全链，导致同一请求执行两遍。
 * 由 {@code SecurityConfig} 手动构造并只用 {@code addFilterBefore} 装入安全链。
 */
public class BearerSessionAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    /** 超长输入直接忽略：避免拿 1 MB 的"令牌"去做哈希。 */
    private static final int MAX_TOKEN_LENGTH = 512;

    private final AuthenticationService authenticationService;

    public BearerSessionAuthenticationFilter(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = resolveToken(request);
            if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                authenticationService.authenticate(token).ifPresent(principal -> bindIdentity(principal));
            }
            filterChain.doFilter(request, response);
        } finally {
            // 顺序与绑定相反：先解绑我们的上下文，再清 Spring Security 的。
            TenantContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private static void bindIdentity(PmsPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = principal.permissions().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        TenantContextHolder.bind(principal.toTenantContext());
    }

    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            return null;
        }
        return token;
    }
}
