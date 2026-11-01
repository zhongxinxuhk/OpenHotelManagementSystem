package cn.sec.hn.openbackupsaas.guest.web;

import cn.sec.hn.openbackupsaas.guest.service.GuestAuthenticationService;
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
 * 住客 Bearer 会话认证过滤器（ADR-019 §2.3）。
 *
 * <p>与员工过滤器（{@code BearerSessionAuthenticationFilter}）是**两个独立的类**，
 * 不是一个类加参数。差异不只是查哪张表：
 * <ul>
 *   <li><b>不绑定 {@code TenantContextHolder}</b>。住客请求绝不能带出租户上下文——
 *       一旦带上，任何依赖"当前租户"的领域代码都会把住客当成某个租户的调用方。
 *       这是本类与员工过滤器最要紧的差别，也是把两者分开写的理由。</li>
 *   <li>授权只有 {@code ROLE_GUEST}：住客没有任何"资源:动作"权限点，
 *       能访问什么完全由 URL 所属的过滤器链决定。</li>
 *   <li>实名状态放入授权集合（{@code GUEST_VERIFIED} / {@code GUEST_NOT_VERIFIED}），
 *       这样"未实名只能用实名相关接口"可以用 {@code @PreAuthorize} 表达，
 *       而不用在每个方法里手写 if。</li>
 * </ul>
 *
 * <p>本类**不加** {@code @Component}：Boot 会把容器里的 Filter Bean 自动注册到
 * Servlet 容器，与安全链里的 {@code addFilterBefore} 叠加后同一请求执行两遍。
 */
public class GuestBearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    /** 超长输入直接忽略：避免拿 1 MB 的"令牌"去做哈希。 */
    private static final int MAX_TOKEN_LENGTH = 512;

    /** 住客身份标记。名字刻意与员工权限点风格不同，避免被误当成一个权限点。 */
    public static final String ROLE_GUEST = "ROLE_GUEST";

    /** 已通过实名的住客。 */
    public static final String GUEST_VERIFIED = "GUEST_VERIFIED";

    /** 未通过实名的住客：只能访问实名相关接口。 */
    public static final String GUEST_NOT_VERIFIED = "GUEST_NOT_VERIFIED";

    private final GuestAuthenticationService guestAuthenticationService;

    public GuestBearerAuthenticationFilter(GuestAuthenticationService guestAuthenticationService) {
        this.guestAuthenticationService = guestAuthenticationService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 只清理本类自己设的身份，不碰 TenantContextHolder：那条 ThreadLocal
        // 由员工链负责，住客链从不写它，所以也无从"清理错对象"。
        try {
            String token = resolveToken(request);
            if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                guestAuthenticationService.authenticate(token)
                        .ifPresent(this::bindIdentity);
            }
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void bindIdentity(cn.sec.hn.openbackupsaas.guest.security.GuestPrincipal principal) {
        boolean verified = principal.verificationStatus().satisfiesRealNameRequirement();
        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority(ROLE_GUEST),
                new SimpleGrantedAuthority(verified ? GUEST_VERIFIED : GUEST_NOT_VERIFIED));
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
