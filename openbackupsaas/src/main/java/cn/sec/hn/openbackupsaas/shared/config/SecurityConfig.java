package cn.sec.hn.openbackupsaas.shared.config;

import cn.sec.hn.openbackupsaas.identity.service.AuthenticationService;
import cn.sec.hn.openbackupsaas.identity.web.BearerSessionAuthenticationFilter;
import cn.sec.hn.openbackupsaas.shared.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;

/**
 * 安全链（S1-01 基线，S1-03 接入会话认证，R-20261101-31 拆成三条平面）。
 *
 * <p><b>三条独立的安全链，按路径前缀划分</b>（ADR-019 §2.4）：
 * <table border="1">
 *   <caption>平面与链</caption>
 *   <tr><th>链</th><th>路径</th><th>认的令牌</th></tr>
 *   <tr><td>{@code guestChain}（Order 1）</td><td>{@code /api/v1/guest/**}</td>
 *       <td>只认 {@code guest_session}</td></tr>
 *   <tr><td>{@code platformChain}（Order 2）</td><td>{@code /api/v1/platform/**}</td>
 *       <td>只认 {@code platform_session}</td></tr>
 *   <tr><td>{@code apiChain}（Order 3）</td><td>其余</td>
 *       <td>只认 {@code user_session}</td></tr>
 * </table>
 *
 * <p>为什么用三条链而不是一条链里判断"这个令牌属于哪个平面"：
 * 单链方案下，认证过滤器必须依次尝试三张表，任何一次"顺手兜底"都会让
 * 一个平面的令牌在另一个平面被认出来——而这正是"角色完全隔离、杜绝越权"
 * 要防的事。分链之后，住客令牌在商家链上根本不会被解析，结果就是 401，
 * 不依赖任何 `if`。
 *
 * <p>其余形态：无状态（服务端会话表 + {@code Authorization: Bearer}，**不用 Cookie**，
 * 因此没有浏览器自动携带凭据的 CSRF 面）；默认拒绝；权限点在方法层用
 * {@code @PreAuthorize} 表达，菜单隐藏与前端路由守卫不构成授权（BL-07）。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** 口令派生：BCrypt 强度 10（默认）。代价与安全性的折中记录在 ADR-016。 */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 住客链。只认住客令牌。
     *
     * <p>匿名放行只限**认证入口四件套**（注册/登录/登出方式清单）：
     * 登出虽要求已认证，但方式清单必须匿名——登录页还没登录就要读它。
     * 不要放宽整个前缀，"这个平面里哪些接口匿名"必须只看这一条规则。
     */
    @Bean
    @Order(1)
    SecurityFilterChain guestFilterChain(HttpSecurity http,
                                         ObjectMapper objectMapper,
                                         AuthenticationEntryPoint authenticationEntryPoint,
                                         AccessDeniedHandler accessDeniedHandler,
                                         cn.sec.hn.openbackupsaas.guest.service.GuestAuthenticationService
                                                 guestAuthenticationService) throws Exception {
        var guestFilter = new cn.sec.hn.openbackupsaas.guest.web.GuestBearerAuthenticationFilter(
                guestAuthenticationService);
        http
                .securityMatcher("/api/v1/guest/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                org.springframework.http.HttpMethod.POST, "/api/v1/guest/auth/register").permitAll()
                        .requestMatchers(
                                org.springframework.http.HttpMethod.POST, "/api/v1/guest/auth/login").permitAll()
                        // 验证码登录两端点（R-20261102-07）：登录前无身份，必须匿名放行
                        .requestMatchers(
                                org.springframework.http.HttpMethod.POST, "/api/v1/guest/auth/code-login/challenge").permitAll()
                        .requestMatchers(
                                org.springframework.http.HttpMethod.POST, "/api/v1/guest/auth/code-login").permitAll()
                        // 忘记密码两端点（R-20261103-01）：匿名——忘记密码本就没有身份
                        //（已登录改密 /password 不放行：默认要求认证）
                        .requestMatchers(
                                org.springframework.http.HttpMethod.POST, "/api/v1/guest/auth/password-reset/challenge").permitAll()
                        .requestMatchers(
                                org.springframework.http.HttpMethod.POST, "/api/v1/guest/auth/password-reset").permitAll()
                        .requestMatchers("/api/v1/guest/auth/methods").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(guestFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 平台链。只认平台令牌；登录接口是这条链上唯一的匿名入口。 */
    @Bean
    @Order(2)
    SecurityFilterChain platformFilterChain(HttpSecurity http,
                                            ObjectMapper objectMapper,
                                            AuthenticationEntryPoint authenticationEntryPoint,
                                            AccessDeniedHandler accessDeniedHandler,
                                            cn.sec.hn.openbackupsaas.platform.service.PlatformAuthenticationService
                                                    platformAuthenticationService) throws Exception {
        var platformFilter = new cn.sec.hn.openbackupsaas.platform.web.PlatformBearerAuthenticationFilter(
                platformAuthenticationService);
        http
                .securityMatcher("/api/v1/platform/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/platform/auth/login").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(platformFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 商家（员工）链：其余全部路径。这条是原有行为，未做改动。 */
    @Bean
    @Order(3)
    SecurityFilterChain apiFilterChain(HttpSecurity http,
                                       ObjectMapper objectMapper,
                                       AuthenticationEntryPoint authenticationEntryPoint,
                                       AccessDeniedHandler accessDeniedHandler,
                                       AuthenticationService authenticationService) throws Exception {
        // 手动构造而不是注册成 Bean：Filter Bean 会被 Boot 自动注册到 Servlet 容器，
        // 与下面 addFilterBefore 叠加后同一请求会执行两次（见过滤器类注释）。
        BearerSessionAuthenticationFilter sessionFilter =
                new BearerSessionAuthenticationFilter(authenticationService);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 健康检查供负载均衡与探针使用，不返回敏感细节（show-details=when-authorized）
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // OpenAPI 文档在生产环境默认关闭（PMS_OPENAPI_ENABLED=false），此处只登记路径
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // 登录是唯一匿名的业务接口：此时还没有身份，只能用租户编码定位账号
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        // 匿名公开面（ADR-017 §2.6）：全系统只有这一个匿名前缀。
                        // 它由 pms.public-api.enabled 控制，关闭时没有映射 → 404（看起来就是不存在）。
                        // 放开整个前缀是有意的：新增匿名接口必须落在这里，
                        // 这样"哪些路径不需要身份"只需要看一条规则，而不是逐个接口去记。
                        .requestMatchers("/api/v1/public/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 未认证：返回 401 + ProblemDetail，而不是重定向到不存在的登录页。 */
    @Bean
    AuthenticationEntryPoint problemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, ex) -> writeProblem(objectMapper, request, response,
                HttpStatus.UNAUTHORIZED, "未认证", "请求需要身份认证");
    }

    /** 已认证但无权限：返回 403，且不泄露目标对象是否存在（BL-07）。 */
    @Bean
    AccessDeniedHandler problemDetailAccessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, ex) -> writeProblem(objectMapper, request, response,
                HttpStatus.FORBIDDEN, "无访问权限", "当前身份无权访问该资源");
    }

    private static void writeProblem(ObjectMapper objectMapper,
                                     HttpServletRequest request,
                                     HttpServletResponse response,
                                     HttpStatus status,
                                     String title,
                                     String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("requestId", String.valueOf(org.slf4j.MDC.get(RequestIdFilter.MDC_KEY)));
        // 与 GlobalExceptionHandler 保持同一套三分类：过滤链拒绝的 401/403 也属于
        // "调用方该改输入或换账号"，客户端不应把它当成待核实的未知结果（S1-11）。
        problem.setProperty("outcome", cn.sec.hn.openbackupsaas.shared.web.ErrorOutcome.REJECTED);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
