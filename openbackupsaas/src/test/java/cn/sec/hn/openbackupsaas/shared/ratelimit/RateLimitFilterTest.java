package cn.sec.hn.openbackupsaas.shared.ratelimit;

import cn.sec.hn.openbackupsaas.shared.observability.PmsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流闸门的行为（J-05）。
 *
 * <p>用真实的 {@link MockHttpServletRequest} 走完整个过滤器，
 * 而不是只测令牌桶：真正会出问题的是**匹配**这一层——路径解码、规则顺序、
 * 以及"到底按谁计数"。这些只测桶是测不到的。
 */
class RateLimitFilterTest {

    private final AtomicLong now = new AtomicLong(5_000_000);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private FilterHarness harness() {
        return harness(new MockEnvironment());
    }

    private FilterHarness harness(MockEnvironment environment) {
        // MockEnvironment 默认只带 DefaultConversionService，认不出 "PT5M" 这类 Duration 字面量；
        // 真实运行由 Boot 的 ApplicationConversionService 负责。不补这一步，
        // 测试会在"窗口解析失败"上红，看起来像限流逻辑写错了
        environment.setConversionService(new ApplicationConversionService());
        RateLimitSettings settings = new RateLimitSettings(environment);
        RateLimitRules rules = new RateLimitRules(environment);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, now::get);
        PmsMetrics metrics = new PmsMetrics(registry);
        var filter = new RateLimitFilter(
                settings, rules, limiter, new ClientIpResolver(settings.trustedProxies()),
                metrics, JsonMapper.builder().build());
        return new FilterHarness(filter);
    }

    @Test
    void rejectsWithProblemJsonAfterTheBudgetIsSpent() throws Exception {
        FilterHarness harness = harness();

        // /api/v1/guest/auth/login 默认 10 次 / 5 分钟
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = harness.post("/api/v1/guest/auth/login", "203.0.113.7", null);
            assertEquals(200, response.getStatus(), "第 " + (i + 1) + " 次应放行");
            assertEquals("10", response.getHeader(RateLimitFilter.HEADER_LIMIT));
        }

        MockHttpServletResponse blocked = harness.post("/api/v1/guest/auth/login", "203.0.113.7", null);

        assertEquals(429, blocked.getStatus());
        assertTrue(blocked.getContentType().startsWith("application/problem+json"),
                "错误体必须是 RFC 9457：" + blocked.getContentType());
        assertEquals("0", blocked.getHeader(RateLimitFilter.HEADER_REMAINING));
        assertTrue(Long.parseLong(blocked.getHeader("Retry-After")) > 0,
                "必须给出可执行的重试时间，而不是让客户端立刻重来");
        String body = blocked.getContentAsString();
        assertTrue(body.contains(RateLimitFilter.CODE), "响应应带稳定错误码：" + body);
        assertFalse(harness.chainInvoked(), "被拒的请求不应继续进入安全链与业务代码");
    }

    // ==================== 按门店配额（R-20261103-02） ====================

    @Test
    void perHotelQuotaIsKeyedByHotelNotClientIp() throws Exception {
        // 店级档位压到 2；IP 档位保持默认 600——本用例只看店级维度
        MockEnvironment environment = new MockEnvironment()
                .withProperty("pms.rate-limit.rules.public.catalog.hotel.permits", "2");
        FilterHarness harness = harness(environment);
        String path = "/api/v1/public/pms-admin/hotels/hq/room-types";

        assertEquals(200, harness.get(path, "203.0.113.7").getStatus());
        // 响应头取声明序第一条（最具体 = 店级）档位的额度
        assertEquals("2", harness.get(path, "203.0.113.7").getHeader(RateLimitFilter.HEADER_LIMIT));

        // 换客户端 IP 仍然被拒：店级键**不含 IP**——防分布式换源，
        // 换 IP 若能重置额度，这条档位等于没做
        MockHttpServletResponse blocked = harness.get(path, "198.51.100.9");
        assertEquals(429, blocked.getStatus());
        assertTrue(blocked.getContentAsString().contains("\"limit\":\"public.catalog.hotel\""),
                "429 应指明命中的是店级档位：" + blocked.getContentAsString());
        assertFalse(harness.chainInvoked(), "被拒的请求不应进入安全链");
    }

    @Test
    void perHotelBucketsAreIsolatedAndStackWithClientQuota() throws Exception {
        // 店级 2 + IP 3：两条叠加——任一拒绝即 429
        MockEnvironment environment = new MockEnvironment()
                .withProperty("pms.rate-limit.rules.public.catalog.hotel.permits", "2")
                .withProperty("pms.rate-limit.rules.public.catalog.permits", "3");
        FilterHarness harness = harness(environment);

        // hq 用掉店级 2 份；beach 用掉 1 份（IP 累计 3 份恰好用尽）
        assertEquals(200, harness.get("/api/v1/public/pms-admin/hotels/hq/room-types", "203.0.113.7").getStatus());
        assertEquals(200, harness.get("/api/v1/public/pms-admin/hotels/hq/room-types", "203.0.113.7").getStatus());
        assertEquals(200, harness.get("/api/v1/public/pms-admin/hotels/beach/room-types", "203.0.113.7").getStatus());

        // beach 店级只用了 1/2，但 IP 已耗尽 → IP 档位拒绝（叠加生效）
        MockHttpServletResponse blocked = harness.get("/api/v1/public/pms-admin/hotels/beach/room-types", "203.0.113.7");
        assertEquals(429, blocked.getStatus());
        assertTrue(blocked.getContentAsString().contains("\"limit\":\"public.catalog\""),
                "IP 维度兜底仍对该路径生效：" + blocked.getContentAsString());

        // 店级桶互相独立：hq 耗尽不影响别的门店——全新门店 + 全新 IP 应放行。
        // （beach 的店级额度已被上一个"到达即计数"的尝试耗尽：被 IP 档拒绝的请求
        // 仍先消耗了店级桶——各桶独立计数它收到的尝试，这是分层限流的标准语义。）
        assertEquals(200, harness.get("/api/v1/public/pms-admin/hotels/cove/room-types", "198.51.100.9").getStatus());
    }

    @Test
    void anotherClientKeepsItsOwnBudget() throws Exception {
        FilterHarness harness = harness();
        for (int i = 0; i < 10; i++) {
            harness.post("/api/v1/guest/auth/login", "203.0.113.7", null);
        }
        assertEquals(429, harness.post("/api/v1/guest/auth/login", "203.0.113.7", null).getStatus());
        assertEquals(200, harness.post("/api/v1/guest/auth/login", "198.51.100.9", null).getStatus(),
                "限流不能把邻居一起挡住");
    }

    @Test
    void spoofedForwardedHeaderDoesNotBuyNewBudget() throws Exception {
        FilterHarness harness = harness();
        for (int i = 0; i < 10; i++) {
            // 每次换一个伪造的转发地址：没有可信代理时必须全部落在同一个桶里
            harness.post("/api/v1/guest/auth/login", "203.0.113.7", "10.0.0." + i);
        }
        assertEquals(429, harness.post("/api/v1/guest/auth/login", "203.0.113.7", "10.0.0.99").getStatus(),
                "伪造 X-Forwarded-For 不得重置额度（否则限流等于没做）");
    }

    @Test
    void pathVariantsThatMvcWouldStillRouteAreAlsoLimited() throws Exception {
        FilterHarness harness = harness();
        for (int i = 0; i < 10; i++) {
            harness.post("/api/v1/guest/auth/login", "203.0.113.7", null);
        }
        // %6C 解码后就是 'l'：Spring MVC 会照常路由到登录接口。
        // 如果限流器拿未解码的原始 URI 去匹配，这里就会漏过去——一个安静的完全绕过
        assertEquals(429, harness.post("/api/v1/guest/auth/%6Cogin", "203.0.113.7", null).getStatus());
    }

    @Test
    void uncoveredPathsAreNotLimitedHere() throws Exception {
        FilterHarness harness = harness();
        for (int i = 0; i < 50; i++) {
            MockHttpServletResponse response = harness.get("/api/v1/reservations", "203.0.113.7");
            assertEquals(200, response.getStatus(), "已登录接口本轮不限流（避免误伤批量导出）");
        }
    }

    @Test
    void budgetRecoversAsTokensRefill() throws Exception {
        FilterHarness harness = harness();
        for (int i = 0; i < 10; i++) {
            harness.post("/api/v1/guest/auth/login", "203.0.113.7", null);
        }
        assertEquals(429, harness.post("/api/v1/guest/auth/login", "203.0.113.7", null).getStatus());

        // 5 分钟窗口走完 → 桶补满
        now.addAndGet(Duration.ofMinutes(5).toNanos());
        assertEquals(200, harness.post("/api/v1/guest/auth/login", "203.0.113.7", null).getStatus());
    }

    @Test
    void disablingTheGatePassesEverything() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("pms.rate-limit.enabled", "false");
        FilterHarness harness = harness(environment);

        for (int i = 0; i < 120; i++) {
            assertEquals(200, harness.post("/api/v1/guest/auth/login", "203.0.113.7", null).getStatus());
        }
    }

    @Test
    void rejectionIsCountedAsAMetricWithLowCardinalityTag() throws Exception {
        FilterHarness harness = harness();
        for (int i = 0; i < 11; i++) {
            harness.post("/api/v1/guest/auth/login", "203.0.113.7", null);
        }

        double count = registry.get("pms.security.rate_limited")
                .tag("rule", "guest.login")
                .counter()
                .count();
        assertEquals(1.0, count, "被拒次数要能进大盘，否则'被刷了'只能靠人翻日志");
    }

    @Test
    void limitsCanBeTightenedByConfiguration() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        // 事故期临时收紧：改环境变量重启即可，不用改代码
        environment.setProperty("pms.rate-limit.rules.guest.login.permits", "2");
        environment.setProperty("pms.rate-limit.rules.guest.login.window", "PT1M");
        FilterHarness harness = harness(environment);

        assertEquals(200, harness.post("/api/v1/guest/auth/login", "203.0.113.7", null).getStatus());
        assertEquals(200, harness.post("/api/v1/guest/auth/login", "203.0.113.7", null).getStatus());
        assertEquals(429, harness.post("/api/v1/guest/auth/login", "203.0.113.7", null).getStatus());
    }

    /** 过滤器 + 调用计数，避免每个用例重复搭 Mock 请求对象。 */
    private static final class FilterHarness {

        private final RateLimitFilter filter;
        private final AtomicBoolean chainCalled = new AtomicBoolean();
        private final AtomicInteger chainCount = new AtomicInteger();

        FilterHarness(RateLimitFilter filter) {
            this.filter = filter;
        }

        MockHttpServletResponse post(String path, String remoteAddr, String forwardedFor) throws Exception {
            return run("POST", path, remoteAddr, forwardedFor);
        }

        MockHttpServletResponse get(String path, String remoteAddr) throws Exception {
            return run("GET", path, remoteAddr, null);
        }

        boolean chainInvoked() {
            return chainCalled.get();
        }

        private MockHttpServletResponse run(String method, String path, String remoteAddr, String forwardedFor)
                throws Exception {
            chainCalled.set(false);
            MockHttpServletRequest request = new MockHttpServletRequest(method, path);
            if (remoteAddr != null) {
                request.setRemoteAddr(remoteAddr);
            }
            if (forwardedFor != null) {
                request.addHeader("X-Forwarded-For", forwardedFor);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = (req, res) -> {
                chainCalled.set(true);
                chainCount.incrementAndGet();
                // 模拟业务返回：过滤器放行后状态码由下游决定
                ((MockHttpServletResponse) res).setStatus(200);
            };
            filter.doFilter(request, response, chain);
            return response;
        }
    }
}
