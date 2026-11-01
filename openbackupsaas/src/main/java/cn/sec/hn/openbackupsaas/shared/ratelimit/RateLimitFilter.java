package cn.sec.hn.openbackupsaas.shared.ratelimit;

import cn.sec.hn.openbackupsaas.shared.observability.PmsMetrics;
import cn.sec.hn.openbackupsaas.shared.web.ErrorOutcome;
import cn.sec.hn.openbackupsaas.shared.web.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;

/**
 * 匿名入口的统一闸门（J-05）。
 *
 * <h2>为什么放在安全链之前</h2>
 * 限流的目的之一是**省下被刷时的成本**：每个带令牌的请求都会查一次
 * {@code user_session}/{@code guest_session}（权限还是每请求实时查库），
 * 登录请求还要跑一次 BCrypt。把闸门放在这些工作之前，被刷时数据库基本不参与。
 *
 * <h2>为什么用 {@link UrlPathHelper} 取路径，而不是 {@code request.getRequestURI()}</h2>
 * 后者是**未解码**的原始 URI，而 Spring MVC 是按解码后的路径选处理器的。
 * 直接用原始 URI 会出现这类绕过：{@code /api/v1/guest/auth/%6Cogin} 在 MVC 里命中登录接口，
 * 在限流器眼里却是个不认识的路径——于是登录完全不限流，而且没有任何报错。
 * {@link UrlPathHelper#getPathWithinApplication} 与 MVC 用同一套解码/去矩阵参数/去上下文路径的规则。
 *
 * <h2>拒绝形态</h2>
 * 429 + RFC 9457 + {@code Retry-After} + {@code X-RateLimit-*} + {@code code=RATE_LIMITED}。
 * {@code outcome} 仍取 {@code REJECTED}：这是"调用方该退避"，不是"结果未知"，
 * 客户端不该去查原交易结果。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** 稳定错误码：客户端据此显示"操作太频繁"，而不是通用错误提示。 */
    public static final String CODE = "RATE_LIMITED";

    public static final String HEADER_LIMIT = "X-RateLimit-Limit";
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";

    private final RateLimitSettings settings;
    private final RateLimitRules rules;
    private final RateLimiter limiter;
    private final ClientIpResolver ipResolver;
    private final PmsMetrics metrics;
    private final ObjectMapper objectMapper;
    private final UrlPathHelper pathHelper = new UrlPathHelper();

    public RateLimitFilter(RateLimitSettings settings,
                           RateLimitRules rules,
                           RateLimiter limiter,
                           ClientIpResolver ipResolver,
                           PmsMetrics metrics,
                           ObjectMapper objectMapper) {
        this.settings = settings;
        this.rules = rules;
        this.limiter = limiter;
        this.ipResolver = ipResolver;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!settings.enabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = pathHelper.getPathWithinApplication(request);
        RateLimitRule rule = rules.resolve(request.getMethod(), path);
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }

        String client = ipResolver.resolve(request);
        RateLimitPolicy policy = rule.policy();
        RateLimitDecision decision = limiter.tryAcquire(policy.name() + "|" + client, policy);

        if (decision.allowed()) {
            response.setHeader(HEADER_LIMIT, String.valueOf(decision.limit()));
            response.setHeader(HEADER_REMAINING, String.valueOf(decision.remaining()));
            chain.doFilter(request, response);
            return;
        }

        metrics.rateLimited(policy.name());
        log.warn("命中限流 rule={} client={} path={} method={} retryAfter={}s requestId={}",
                policy.name(), client, path, request.getMethod(), decision.retryAfterSeconds(),
                MDC.get(RequestIdFilter.MDC_KEY));

        response.setHeader(HEADER_LIMIT, String.valueOf(decision.limit()));
        response.setHeader(HEADER_REMAINING, "0");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        writeProblem(request, response, rule, decision);
    }

    private void writeProblem(HttpServletRequest request,
                              HttpServletResponse response,
                              RateLimitRule rule,
                              RateLimitDecision decision) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "请求过于频繁，请在 " + decision.retryAfterSeconds() + " 秒后重试");
        problem.setTitle("请求过于频繁");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("requestId", String.valueOf(MDC.get(RequestIdFilter.MDC_KEY)));
        problem.setProperty("outcome", ErrorOutcome.REJECTED);
        problem.setProperty("code", CODE);
        problem.setProperty("retryAfterSeconds", decision.retryAfterSeconds());
        // 只暴露档位名，不暴露窗口额度的内部实现细节；客户端要的是"等多久"
        problem.setProperty("limit", rule.policy().name());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
