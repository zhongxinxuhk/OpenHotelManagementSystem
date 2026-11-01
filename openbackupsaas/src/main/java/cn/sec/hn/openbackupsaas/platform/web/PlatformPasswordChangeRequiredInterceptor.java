package cn.sec.hn.openbackupsaas.platform.web;

import cn.sec.hn.openbackupsaas.platform.security.PlatformPrincipal;
import cn.sec.hn.openbackupsaas.shared.error.PasswordChangeRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 平台账号的强制首次改密拦截（ADR-019 §2.3）。
 *
 * <p>平台动作影响面是全局的（审实名、改核验渠道）。种子脚本创建的账号带
 * {@code must_change_password = true}：在改掉初始口令之前，除了改密、登出与
 * "我是谁"，这个账号在平台平面**什么都做不了**——包括审核。
 *
 * <p>为什么不复用员工侧的 {@code PasswordChangeRequiredInterceptor}：两个平面的
 * 放行清单不同（{@code /api/v1/auth/*} vs {@code /api/v1/platform/auth/*}），
 * 主体类型也不同。合成一个类意味着放行清单里同时挂着两个平面的路径，
 * 哪天清单写串了，两个平面会同时出问题。
 *
 * <p>放在拦截器而非过滤器、不用 {@code @PreAuthorize} 的理由与员工侧完全相同，
 * 见那个类的注释：过滤器抛的异常到不了统一错误处理，逐接口加表达式一定会漏。
 */
public class PlatformPasswordChangeRequiredInterceptor implements HandlerInterceptor {

    /** 待改密状态下仍允许调用的平台接口：改密本身、退出、查看当前身份。 */
    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/api/v1/platform/auth/password",
            "/api/v1/platform/auth/logout",
            "/api/v1/platform/auth/me");

    /** 只约束平台业务接口。 */
    private static final String PLATFORM_PREFIX = "/api/v1/platform/";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = pathOf(request);
        if (!path.startsWith(PLATFORM_PREFIX) || ALLOWED_PATHS.contains(path)) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof PlatformPrincipal principal
                && principal.mustChangePassword()) {
            throw new PasswordChangeRequiredException(
                    "账号仍在使用初始口令，请先修改口令后再使用平台功能");
        }
        return true;
    }

    /** 去掉 contextPath：部署在子路径下时 {@code getRequestURI} 会带前缀，直接比会全部失配。 */
    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
