package cn.sec.hn.openbackupsaas.tenant;

import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.support.IdentitySeed;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 门店清单接口验收（S3-01 前端对接）。
 *
 * <p>本接口是前端所有业务页的入口——前端必须先知道"我有���些门店"，
 * 才能构造 {@code /api/v1/hotels/{hotelId}/...} 路径。所以它的边界比一般列表接口更敏感：
 * <ul>
 *   <li>返回的是**数据范围**，不是租户全量。范围外的门店不能出现，否则前端会把
 *       点不到的门店放进下拉框，用户提交后才撞上 403；</li>
 *   <li>范围为空时必须返回**空数组**而不是全量——空集合与全集必须区分，
 *       否则"撤权"就变成了"扩权"；</li>
 *   <li>无 token → 401；无 {@code org:read} → 403。</li>
 * </ul>
 */
@AutoConfigureMockMvc
class HotelScopeApiTest extends PostgresIntegrationTest {

    private static final String[] ORG_PERMISSIONS = {"org:read"};

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
    void unauthenticatedRequestGets401() throws Exception {
        seed("h1", ORG_PERMISSIONS, true);

        mockMvc.perform(get("/api/v1/hotels"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingOrgReadPermissionGets403() throws Exception {
        IdentitySeed.Seed seed = seed("h2", new String[]{"identity:read"}, true);
        String token = login(seed);

        mockMvc.perform(get("/api/v1/hotels")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("无访问权限"));
    }

    @Test
    void listsOnlyHotelsInsideDataScope() throws Exception {
        // 该账号只被授予第一家门店；租户下实际有两家门店
        IdentitySeed.Seed seed = seed("h3", ORG_PERMISSIONS, true);
        String token = login(seed);

        mockMvc.perform(get("/api/v1/hotels")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(seed.hotelId().toString()))
                .andExpect(jsonPath("$[0].code").value("hh3"))
                .andExpect(jsonPath("$[0].timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void emptyDataScopeReturnsEmptyArrayNotAllHotels() throws Exception {
        // 关键用例：范围为空 ≠ 全部。若实现退化成"不过滤"，这里会返回租户全部门店，
        // 撤权就变成了扩权——这是本接口最容易写错、也最危险的一处。
        IdentitySeed.Seed seed = seed("h4", ORG_PERMISSIONS, false);
        String token = login(seed);

        mockMvc.perform(get("/api/v1/hotels")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void responseDoesNotLeakTenantId() throws Exception {
        IdentitySeed.Seed seed = seed("h5", ORG_PERMISSIONS, true);
        String token = login(seed);

        // 租户归属由服务端会话推导，重复下发只会让调用方误以为可以自行指定租户
        mockMvc.perform(get("/api/v1/hotels")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenantId").doesNotExist());
    }

    @Test
    void hotelOfAnotherTenantIsNeverListed() throws Exception {
        IdentitySeed.Seed mine = seed("h6", ORG_PERMISSIONS, true);
        IdentitySeed.Seed other = seed("h7", ORG_PERMISSIONS, true);
        String token = login(mine);

        String body = mockMvc.perform(get("/api/v1/hotels")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains(other.hotelId().toString()),
                "他租户门店不应出现在当前身份的门店清单里");
        org.junit.jupiter.api.Assertions.assertTrue(body.contains(mine.hotelId().toString()));
    }

    private IdentitySeed.Seed seed(String suffix, String[] permissions, boolean grantFirstHotel) {
        return IdentitySeed.seed(organizationService, identityMapper, passwordEncoder, suffix, permissions,
                grantFirstHotel);
    }

    private String login(IdentitySeed.Seed seed) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "tenantCode", seed.tenantCode(),
                "username", seed.username(),
                "password", IdentitySeed.RAW_PASSWORD));
        String response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asString();
    }
}
