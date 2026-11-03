package cn.sec.hn.openbackupsaas.platform.web;

import cn.sec.hn.openbackupsaas.platform.security.PlatformPrincipal;
import cn.sec.hn.openbackupsaas.platform.service.PlatformAuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 平台 Bearer 会话认证过滤器（ADR-019 §2.3）。
 *
 * <p>授权集合直接用**动作码本身**（{@code guest.verification.review}）作为 authority，
 * 所以在控制器上写 {@code @PreAuthorize("hasAuthority('guest.verification.review')")}
 * 就能生效，不需要再套一层 {@code ROLE_} 前缀。
 *
 * <p>同样**不绑定 {@code TenantContextHolder}**：平台不跨租户（DEC-02），
 * 一旦绑上，任何按"当前租户"取数的代码都会把平台调用当成某个租户的调用方。
 *
 * <p>本类不加 {@code @Component}：容器里的 Filter Bean 会被 Boot 自动注册到
 * Servlet 容器，与安全链里的 {@code addFilterBefore} 叠加后同一请求执行两遍。
 */
public class PlatformBearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";
    private static final int MAX_TOKEN_LENGTH = 512;

    private final PlatformAuthenticationService platformAuthenticationService;

    public PlatformBearerAuthenticationFilter(PlatformAuthenticationService platformAuthenticationService) {
        this.platformAuthenticationService = platformAuthenticationService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = resolveToken(request);
            if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                platformAuthenticationService.authenticate(token)
                        .ifPresent(this::bindIdentity);
            }
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void bindIdentity(PlatformPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = principal.actions().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
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
