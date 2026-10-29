package cn.sec.hn.openbackupsaas.identity;

import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.support.IdentitySeed;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S1-03 接口层验收：401 / 403 / 404 / 200 的真实 HTTP 行为。
 *
 * <p>与 {@link AuthIntegrationTest} 的分工：那边测服务与数据库不变量，这里测
 * 「安全链是否真的接上了」——令牌头、权限点、租户收窄、登出后的失效，都必须体现在状态码上。
 * 用 MockMvc 走完整过滤器链（含 {@code BearerSessionAuthenticationFilter}），不是直接调用控制器。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@EnabledIfEnvironmentVariable(named = "PMS_IT_DB", matches = "true")
@Sql(scripts = "/db/integration-cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AuthApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private IdentityMapper identityMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void unauthenticatedRequestGetsProblemDetail401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("未认证"));
    }

    @Test
    void loginThenMeThenLogout() throws Exception {
        IdentitySeed.Seed seed = seed("c1", new String[]{"identity:read"});

        String token = login(seed);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(seed.username()))
                .andExpect(jsonPath("$.tenantId").value(seed.tenantId().toString()))
                .andExpect(jsonPath("$.hotelIds[0]").value(seed.hotelId().toString()));

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void badPasswordGets401WithSameMessageAsUnknownUser() throws Exception {
        IdentitySeed.Seed seed = seed("c2", new String[0]);

        String wrongPassword = loginFailure(seed.tenantCode(), seed.username(), "Wrong-Password-1");
        String unknownUser = loginFailure(seed.tenantCode(), "nobody" + seed.username(), IdentitySeed.RAW_PASSWORD);

        org.junit.jupiter.api.Assertions.assertEquals(wrongPassword, unknownUser);
    }

    @Test
    void missingPermissionGets403() throws Exception {
        IdentitySeed.Seed seed = seed("c3", new String[0]);
        String token = login(seed);

        mockMvc.perform(get("/api/v1/identity/users/" + seed.userId() + "/hotel-scopes")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("无访问权限"));
    }

    @Test
    void permittedUserReadsOwnTenantScopes() throws Exception {
        IdentitySeed.Seed seed = seed("c4", new String[]{"identity:read"});
        String token = login(seed);

        mockMvc.perform(get("/api/v1/identity/users/" + seed.userId() + "/hotel-scopes")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(seed.hotelId().toString()));
    }

    @Test
    void crossTenantUserIdReturns404NotForbidden() throws Exception {
        IdentitySeed.Seed seedA = seed("c5", new String[]{"identity:read"});
        IdentitySeed.Seed seedB = IdentitySeed.seed(organizationService, identityMapper, passwordEncoder,
                "c6", new String[0], false);

        String token = login(seedA);

        mockMvc.perform(get("/api/v1/identity/users/" + seedB.userId() + "/hotel-scopes")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("对象不存在"));

        // 同一路径下"不存在的 id"与"别的租户的 id"必须是同一个响应，否则就是对象存在性泄露
        mockMvc.perform(get("/api/v1/identity/users/" + UUID.randomUUID() + "/hotel-scopes")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    private IdentitySeed.Seed seed(String suffix, String[] permissions) {
        return IdentitySeed.seed(organizationService, identityMapper, passwordEncoder, suffix, permissions, true);
    }

    private String login(IdentitySeed.Seed seed) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "tenantCode", seed.tenantCode(),
                "username", seed.username(),
                "password", IdentitySeed.RAW_PASSWORD));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return String.valueOf(payload.get("token"));
    }

    private String loginFailure(String tenantCode, String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "tenantCode", tenantCode,
                "username", username,
                "password", password));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return String.valueOf(payload.get("detail"));
    }
}
