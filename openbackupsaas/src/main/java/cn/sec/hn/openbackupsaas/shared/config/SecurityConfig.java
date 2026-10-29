package cn.sec.hn.openbackupsaas.shared.config;

import cn.sec.hn.openbackupsaas.shared.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;

/**
 * 安全基线（S1-01）：**默认拒绝**，不提供任何可用的登录方式。
 *
 * <p>这不是"认证已完成"。登录、会话、RBAC、门店数据范围与租户隔离在 S1-03 实现；
 * 在此之前，除健康检查与 API 文档外，所有请求都应被拒绝。
 * 菜单隐藏或前端路由守卫不能替代服务端授权（BL-07）。
 *
 * <p>CSRF 当前关闭的原因：尚无 Cookie 会话，接口计划为无状态 JSON API。
 * 一旦 S1-03 决定改用 Cookie 会话，**必须重新打开 CSRF 并补同步令牌**，不能沿用本配置。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiFilterChain(HttpSecurity http,
                                       ObjectMapper objectMapper,
                                       AuthenticationEntryPoint authenticationEntryPoint,
                                       AccessDeniedHandler accessDeniedHandler) throws Exception {
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
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
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
