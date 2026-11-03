package cn.sec.hn.openbackupsaas.shared.ratelimit;

import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 限流在真实安全链与 MVC 之上确实生效（J-05）。
 *
 * <h2>为什么单测之外还要这一层</h2>
 * {@code RateLimitFilterTest} 证明过滤器自己的逻辑对；这里证明它**真的挂在链上**：
 * 被 Spring 注册、在安全链之前执行、对真实路由生效、429 的响应形态符合契约。
 * 过滤器写成 {@code @Component} 但没被 Servlet 容器注册，是这类改造最常见的翻车方式，
 * 而且不会有任何报错——只有这一个类能发现。
 *
 * <h2>为什么要独立上下文与独立来源地址</h2>
 * 令牌桶是进程内状态，Spring 的测试上下文按配置缓存：本类用 {@code @TestPropertySource}
 * 把额度压到 2，因此拿到的是**自己的**上下文与自己的桶。
 * 即便如此，同一个类里的多个用例仍共用这批桶，所以每个用例用一个不同的来源地址，
 * 让"第几个用例"不影响结果。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "pms.rate-limit.enabled=true",
        "pms.rate-limit.rules.guest.login.permits=2",
        "pms.rate-limit.rules.guest.login.window=PT1M",
        "pms.rate-limit.rules.staff.login.permits=2",
        "pms.rate-limit.rules.staff.login.window=PT1M",
        "pms.rate-limit.rules.public.catalog.permits=2",
        "pms.rate-limit.rules.public.catalog.window=PT1M"
})
class RateLimitEnforcementTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void guestLoginIsLimitedAndTellsTheClientHowLongToWait() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(guestLogin().with(from("198.51.100.11")))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(guestLogin().with(from("198.51.100.11")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string(RateLimitFilter.HEADER_LIMIT, "2"))
                .andExpect(jsonPath("$.code").value(RateLimitFilter.CODE))
                // 仍然是"调用方该退避"，不是"结果未知"：客户端不该去查原交易结果
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void anotherClientKeepsWorking() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(guestLogin().with(from("198.51.100.12")))
                    .andExpect(status().isUnauthorized());
        }
        // 同一个限流档位、不同来源：互不影响
        mockMvc.perform(guestLogin().with(from("198.51.100.13")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void spoofedForwardedHeaderCannotResetTheBudget() throws Exception {
        // 默认 pms.rate-limit.trusted-proxies 为空 = 谁都不信转发头。
        // 这台机器后面没有反代，采信转发头就等于让任何人自带无限额度
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(guestLogin().with(from("198.51.100.14"))
                            .header("X-Forwarded-For", "203.0.113." + i))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(guestLogin().with(from("198.51.100.14"))
                        .header("X-Forwarded-For", "203.0.113.200"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void eachRuleHasItsOwnBudget() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "tenantCode", "pms-admin",
                                    "username", "admin",
                                    "password", "Wrong-Password-2026")))
                            .with(from("198.51.100.15")))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "pms-admin",
                                "username", "admin",
                                "password", "Wrong-Password-2026")))
                        .with(from("198.51.100.15")))
                .andExpect(status().isTooManyRequests());

        // 员工登录额度用尽，不影响住客登录额度（档位各自独立）
        mockMvc.perform(guestLogin().with(from("198.51.100.15")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicCatalogIsGatedEvenThoughTheControllerIsClosed() throws Exception {
        // 公开面默认关闭（pms.public-api.enabled=false）：控制器没有注册，路径 404。
        // 这里要证明的是闸门在**路由之前**——因此先 404、之后变成 429。
        // 如果哪天限流退化成"进了 MVC 才判断"，这个用例会立刻红。
        String path = "/api/v1/public/pms-admin/hotels/hq/room-types?from=2026-11-10&to=2026-11-12";
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get(path).with(from("198.51.100.16")))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get(path).with(from("198.51.100.16")))
                .andExpect(status().isTooManyRequests());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder guestLogin() {
        return post("/api/v1/guest/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"nobody.here","password":"Wrong-Password-2026"}
                        """);
    }

    private static RequestPostProcessor from(String remoteAddr) {
        return request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
        };
    }
}
