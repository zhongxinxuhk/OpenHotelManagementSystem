package cn.sec.hn.openbackupsaas.shared.config;

import cn.sec.hn.openbackupsaas.identity.service.AuthenticationService;
import cn.sec.hn.openbackupsaas.identity.web.BearerSessionAuthenticationFilter;
import cn.sec.hn.openbackupsaas.shared.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * 安全链（S1-01 基线，S1-03 接入会话认证）。
 *
 * <p>当前形态：
 * <ul>
 *   <li>无状态：服务端会话表 + {@code Authorization: Bearer} 头，**不使用 Cookie**，
 *       因此不存在"浏览器自动携带凭据"的跨站请求伪造面，CSRF 保持关闭。
 *       一旦改回 Cookie 会话，**必须先打开 CSRF 并补同步令牌**，不能沿用本配置。</li>
 *   <li>默认拒绝：只有健康检查、API 文档与登录接口匿名，其余全部要求已认证。</li>
 *   <li>权限点在方法层用 {@code @PreAuthorize} 表达；菜单隐藏与前端路由守卫不构成授权（BL-07）。</li>
 * </ul>
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

    @Bean
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
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
