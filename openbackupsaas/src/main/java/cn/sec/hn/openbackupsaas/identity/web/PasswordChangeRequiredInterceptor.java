package cn.sec.hn.openbackupsaas.identity.web;

import cn.sec.hn.openbackupsaas.shared.error.PasswordChangeRequiredException;
import cn.sec.hn.openbackupsaas.shared.security.PmsPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 强制首次改密拦截（V13）。
 *
 * <h2>为什么放在拦截器而不是过滤器</h2>
 * 过滤器不是 Spring MVC 的一部分，那里抛出的异常到不了 {@code @RestControllerAdvice}，
 * 只能手写 JSON。手写就得复制一份 ProblemDetail 的字段与 {@code outcome} 三分类，
 * 迟早与统一错误响应产生差异。拦截器在 DispatcherServlet 之内，
 * 抛出的异常走同一条 {@code HandlerExceptionResolver} 链路，错误形态天然一致。
 *
 * <h2>为什么不用 {@code @PreAuthorize}</h2>
 * 权限表达式描述的是"能不能做某个动作"，而这里描述的是"整个身份处于受限状态"。
 * 逐个接口加表达式的话，新增接口时一定会漏掉一个——漏掉的那次就是豁免。
 *
 * <h2>放行范围</h2>
 * 只放行改密、登出与"我是谁"三个接口。除此之外**任何** {@code /api} 下的请求
 * （不分 GET 还是写操作）一律拒绝。
 */
public class PasswordChangeRequiredInterceptor implements HandlerInterceptor {

    /** 待改密状态下仍允许调用的接口：改密本身、退出、以及查看自己当前身份。 */
    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/api/v1/auth/password",
            "/api/v1/auth/logout",
            "/api/v1/auth/me");

    /** 只约束业务接口；{@code /actuator}、文档等不在其列。 */
    private static final String API_PREFIX = "/api/";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = pathOf(request);
        if (!path.startsWith(API_PREFIX) || ALLOWED_PATHS.contains(path)) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof PmsPrincipal principal
                && principal.mustChangePassword()) {
            // 文案要能直接指导动作：只说"禁止访问"会让人以为是权限配错了。
            throw new PasswordChangeRequiredException(
                    "账号仍在使用管理员发放的初始口令，请先修改口令后再使用其它功能");
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
