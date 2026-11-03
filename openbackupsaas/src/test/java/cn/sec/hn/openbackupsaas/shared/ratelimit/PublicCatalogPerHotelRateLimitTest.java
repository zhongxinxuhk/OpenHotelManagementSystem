package cn.sec.hn.openbackupsaas.shared.ratelimit;

import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 按门店配额在**真实安全链与 MVC 之上**生效（R-20261103-02，J-02）。
 *
 * <h2>与 {@code RateLimitFilterTest} 的分工</h2>
 * 单测证明过滤器逻辑对；本类证明店级档位真的挂在链上、
 * 且与 IP 档位**叠加**（任一拒绝即 429，429 指明命中的档位）。
 *
 * <h2>为什么独立上下文</h2>
 * 令牌桶是进程内状态、Spring 测试上下文按配置缓存：本类把
 * 店级额度压到 2、IP 额度压到 10，拿到自己的桶。
 * 公开面控制器在本档案默认关闭——放行的请求 404，
 * 这正好复用"闸门在路由之前"的既有证明（R-20261101-34）。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "pms.rate-limit.enabled=true",
        "pms.rate-limit.rules.public.catalog.hotel.permits=2",
        "pms.rate-limit.rules.public.catalog.hotel.window=PT1M",
        "pms.rate-limit.rules.public.catalog.permits=10",
        "pms.rate-limit.rules.public.catalog.window=PT1M"
})
class PublicCatalogPerHotelRateLimitTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void hotelQuotaDeniesEvenWhenClientBudgetRemains() throws Exception {
        // 同一门店第 3 次被**店级**额度拒绝——此时该 IP 只用了 2/10，
        // 证明拒绝来自不含 IP 的店级键（换 IP 也逃不掉）
        String path = "/api/v1/public/pms-admin/hotels/hq/room-types?from=2026-11-10&to=2026-11-12";
        mockMvc.perform(get(path).with(from("198.51.100.31"))).andExpect(status().isNotFound());
        mockMvc.perform(get(path).with(from("198.51.100.32"))).andExpect(status().isNotFound());

        mockMvc.perform(get(path).with(from("198.51.100.31")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.limit").value("public.catalog.hotel"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    @Test
    void hotelBucketsAreIsolatedPerHotel() throws Exception {
        // 两个门店各自的店级额度互不影响
        String alpha = "/api/v1/public/pms-admin/hotels/alpha/room-types?from=2026-11-10&to=2026-11-12";
        String beta = "/api/v1/public/pms-admin/hotels/beta/room-types?from=2026-11-10&to=2026-11-12";
        mockMvc.perform(get(alpha).with(from("198.51.100.33"))).andExpect(status().isNotFound());
        mockMvc.perform(get(alpha).with(from("198.51.100.33"))).andExpect(status().isNotFound());
        mockMvc.perform(get(alpha).with(from("198.51.100.34")))
                .andExpect(status().isTooManyRequests());

        // alpha 耗尽不影响 beta
        mockMvc.perform(get(beta).with(from("198.51.100.35"))).andExpect(status().isNotFound());
    }

    @Test
    void clientQuotaStillStacksAcrossHotels() throws Exception {
        // IP 维度兜底仍在：每家店只打 1 次（店级都不耗尽），
        // 第 11 次跨店累计耗尽 IP 额度 → 429 指明 public.catalog
        for (int i = 1; i <= 10; i++) {
            String path = "/api/v1/public/pms-admin/hotels/h%d/room-types?from=2026-11-10&to=2026-11-12"
                    .formatted(i);
            mockMvc.perform(get(path).with(from("198.51.100.36")))
                    .andExpect(status().isNotFound());
        }
        String eleventh = "/api/v1/public/pms-admin/hotels/h11/room-types?from=2026-11-10&to=2026-11-12";
        mockMvc.perform(get(eleventh).with(from("198.51.100.36")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.limit").value("public.catalog"));
    }

    private static RequestPostProcessor from(String remoteAddr) {
        return request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
        };
    }
}
