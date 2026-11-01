package cn.sec.hn.openbackupsaas.identity;

import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.support.IdentitySeed;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 员工与角色管理接口验收（S3-02）。
 *
 * <h2>这个类在防什么</h2>
 * 管理面的错通常不是"改不成"，而是"改得太多或改错了人"，而且**照样返回 200**：
 * <ul>
 *   <li>停用账号若不撤销会话，被停用者继续可用——接口返回成功，问题在几天后才被发现；</li>
 *   <li>门店范围若写成"只能加不能减"，界面勾掉了但权限还在，撤权静默失效；</li>
 *   <li>停用自己不拦，管理员把自己锁在门外，而系统没有第二个恢复入口；</li>
 *   <li>密码哈希若顺手序列化出去，离线爆破门槛从"猜口令"降到"跑字典"。</li>
 * </ul>
 * 每一条都只能用"行为断言"发现，看代码看不出来。
 */
@AutoConfigureMockMvc
class IdentityAdminApiTest extends PostgresIntegrationTest {

    private static final String[] ADMIN_PERMISSIONS = {
            "identity:read", "identity:write", "org:read"
    };

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

    // ==================== 权限 ====================

    @Test
    void unauthenticatedRequestGets401() throws Exception {
        mockMvc.perform(get("/api/v1/identity/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readPermissionCannotWrite() throws Exception {
        // 只读账号不能建角色：能看名单不等于能改授权。
        IdentitySeed.Seed seed = seed("ia1", new String[]{"identity:read"});
        String token = login(seed);

        mockMvc.perform(post("/api/v1/identity/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "OPS",
                                "name", "运维",
                                "permissionCodes", List.of("room:read")))))
                .andExpect(status().isForbidden());
    }

    // ==================== 权限点字典 ====================

    @Test
    void permissionDictionaryIsGlobalAndNotEmpty() throws Exception {
        IdentitySeed.Seed seed = seed("ia2", ADMIN_PERMISSIONS);
        String token = login(seed);

        // 字典是全局的，不按租户过滤：新租户也应该看到完整字典，
        // 否则管理员没法给角色授权（他会以为"我这没有这个权限点"）。
        mockMvc.perform(get("/api/v1/identity/permissions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(5)))
                .andExpect(jsonPath("$[0].code").exists())
                .andExpect(jsonPath("$[0].name").exists());
    }

    // ==================== 角色 ====================

    @Test
    void createRoleThenListIt() throws Exception {
        IdentitySeed.Seed seed = seed("ia3", ADMIN_PERMISSIONS);
        String token = login(seed);

        mockMvc.perform(post("/api/v1/identity/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "front_office",
                                "name", "前台",
                                "permissionCodes", List.of("room:read", "reservation:read")))))
                .andExpect(status().isCreated())
                // 编码统一转大写：编码会进日志与审计，风格混杂会拖累检索
                .andExpect(jsonPath("$.code").value("FRONT_OFFICE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.permissionCodes.length()").value(2));

        mockMvc.perform(get("/api/v1/identity/roles")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code == 'FRONT_OFFICE')]").exists());
    }

    @Test
    void duplicateRoleCodeGets409() throws Exception {
        IdentitySeed.Seed seed = seed("ia4", ADMIN_PERMISSIONS);
        String token = login(seed);
        createRole(token, "HOUSEKEEPING", "客房");

        mockMvc.perform(post("/api/v1/identity/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "HOUSEKEEPING", "name", "客房2"))))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownPermissionCodeIsRejected() throws Exception {
        IdentitySeed.Seed seed = seed("ia5", ADMIN_PERMISSIONS);
        String token = login(seed);

        // 数据库外键也会拒绝，但那时调用方看到的是一条 PostgreSQL 约束违例；
        // 服务层先给出可读错误，且必须是 4xx 而不是 500。
        mockMvc.perform(post("/api/v1/identity/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "BADROLE",
                                "name", "坏角色",
                                "permissionCodes", List.of("not:a_permission")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("权限点不存在")));
    }

    @Test
    void invalidRoleCodeFormatGets400() throws Exception {
        IdentitySeed.Seed seed = seed("ia6", ADMIN_PERMISSIONS);
        String token = login(seed);

        mockMvc.perform(post("/api/v1/identity/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "bad code!", "name", "格式错误"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disablingRoleRemovesItsPermissionsImmediately() throws Exception {
        // 这是 B-02「停用角色立即失去权限」的端到端证据。
        // 权限是每请求实时查库的，所以停用后**不需要等会话过期**，下一次请求就该 403。
        //
        // 两个账号必须在**同一租户**：IdentitySeed.seed 每次建独立租户，
        // 跨租户操作会被正确地拒成 404，那样测的就不是本用例想验的东西了。
        IdentitySeed.Seed host = seed("ia7", ADMIN_PERMISSIONS);
        IdentitySeed.Teammate target = teammate(host, "ra", new String[]{"identity:read"}, true);
        String operatorToken = login(host);
        String targetToken = loginTeammate(target);

        // 停用前：目标账号能读员工列表（权限来自它自己的角色）
        mockMvc.perform(get("/api/v1/identity/users")
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());

        String targetRoleId = roleIdByCode(host.tenantId(), "ROLE_RA");
        mockMvc.perform(post("/api/v1/identity/roles/" + targetRoleId + "/status")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "INACTIVE"))))
                .andExpect(status().isOk());

        // 停用后：**同一个令牌**的下一次请求被拒——不用等会话过期
        mockMvc.perform(get("/api/v1/identity/users")
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isForbidden());
    }

    // ==================== 员工 ====================

    @Test
    void userListNeverExposesPasswordHash() throws Exception {
        // BCrypt 哈希一旦出接口，离线爆破门槛从"猜口令"降到"跑字典"。
        IdentitySeed.Seed seed = seed("ia8", ADMIN_PERMISSIONS);
        String token = login(seed);

        String body = mockMvc.perform(get("/api/v1/identity/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Assertions.assertFalse(body.contains("passwordHash"), "响应里出现了 passwordHash 字段");
        Assertions.assertFalse(body.contains("$2a$"), "响应里出现了 BCrypt 哈希值");
    }

    @Test
    void userDetailIncludesRolesAndHotelScopes() throws Exception {
        IdentitySeed.Seed seed = seed("ia9", ADMIN_PERMISSIONS);
        String token = login(seed);

        mockMvc.perform(get("/api/v1/identity/users/" + seed.userId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(seed.username()))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0].code").value("ADMIN"))
                // IdentitySeed 把第一家门店加入范围，第二家故意不加
                .andExpect(jsonPath("$.hotelIds.length()").value(1))
                .andExpect(jsonPath("$.hotelIds[0]").value(seed.hotelId().toString()));
    }

    @Test
    void disablingUserRevokesItsSessionsImmediately() throws Exception {
        // 「停用」如果只禁止新登录，已登录的设备继续可用 —— 那它就不是停用。
        IdentitySeed.Seed host = seed("ia10", ADMIN_PERMISSIONS);
        IdentitySeed.Teammate victim = teammate(host, "vd", new String[]{"identity:read"}, true);
        String adminToken = login(host);
        String victimToken = loginTeammate(victim);

        // 停用前：被停用者能正常访问
        mockMvc.perform(get("/api/v1/identity/users")
                        .header("Authorization", "Bearer " + victimToken))
                .andExpect(status().isOk());

        changeUserStatus(adminToken, victim.userId(), "DISABLED");

        // 停用后：**同一个令牌**立即失效
        mockMvc.perform(get("/api/v1/identity/users")
                        .header("Authorization", "Bearer " + victimToken))
                .andExpect(status().isUnauthorized());

        // 且无法再登录
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", host.tenantCode(),
                                "username", victim.username(),
                                "password", IdentitySeed.RAW_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cannotDisableSelf() throws Exception {
        // 把自己锁在门外后**没有任何恢复入口**（平台运维身份 A-05 仍是 BLOCKED）。
        // 这条必须由服务层拦，不能只靠前端不显示按钮。
        IdentitySeed.Seed seed = seed("ia12", ADMIN_PERMISSIONS);
        String token = login(seed);

        mockMvc.perform(post("/api/v1/identity/users/" + seed.userId() + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("不能停用自己")));

        // 而且真的没被停用：仍能访问
        mockMvc.perform(get("/api/v1/identity/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void enablingDisabledUserRestoresAccess() throws Exception {
        IdentitySeed.Seed host = seed("ia13", ADMIN_PERMISSIONS);
        IdentitySeed.Teammate victim = teammate(host, "ve", new String[]{"identity:read"}, true);
        String adminToken = login(host);

        changeUserStatus(adminToken, victim.userId(), "DISABLED");
        changeUserStatus(adminToken, victim.userId(), "ACTIVE");

        // 恢复后可以重新登录（旧会话已被撤销，必须重新认证）
        String token = loginTeammate(victim);
        mockMvc.perform(get("/api/v1/identity/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ==================== 门店范围 ====================

    @Test
    void hotelScopesReplaceIsFullReplacementNotAppend() throws Exception {
        // 界面上的门店范围是全量语义：勾掉一个就必须真的失效。
        // 只 upsert（ON CONFLICT DO NOTHING）等于只能加不能减，撤权会静默失效。
        IdentitySeed.Seed host = seed("ia15", ADMIN_PERMISSIONS);
        IdentitySeed.Teammate target = teammate(host, "hb", new String[]{"identity:read"}, true);
        String token = login(host);

        // 初始：只有第一家门店
        Assertions.assertEquals(List.of(host.hotelId()), hotelScopes(token, target.userId()));

        // 替换为只剩第二家
        mockMvc.perform(put("/api/v1/identity/users/" + target.userId() + "/hotel-scopes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "hotelIds", List.of(host.secondHotelId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotelIds.length()").value(1))
                .andExpect(jsonPath("$.hotelIds[0]").value(host.secondHotelId().toString()));

        // 关键断言：第一家**真的被移除**了，不是"两家都在"
        Assertions.assertEquals(List.of(host.secondHotelId()),
                hotelScopes(token, target.userId()),
                "全量替换后不应保留原门店；若保留说明实现退化成 upsert");
    }

    @Test
    void emptyHotelScopesRevokesAllAccess() throws Exception {
        // 传空数组 = 收回全部门店权限。这是**合法**操作（例如临时冻结某人），
        // 不是参数错误。之后该账号访问任何门店的业务接口都应 403。
        IdentitySeed.Seed host = seed("ia17", new String[]{"identity:read", "identity:write", "room:read"});
        IdentitySeed.Teammate target = teammate(host, "hc", new String[]{"room:read"}, true);
        String adminToken = login(host);
        String targetToken = loginTeammate(target);

        mockMvc.perform(get("/api/v1/hotels/" + host.hotelId() + "/rooms")
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/identity/users/" + target.userId() + "/hotel-scopes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("hotelIds", List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotelIds.length()").value(0));

        // 范围空了 → 该门店不再在范围内 → 403
        mockMvc.perform(get("/api/v1/hotels/" + host.hotelId() + "/rooms")
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isForbidden());
    }

    // ==================== 跨租户 ====================

    @Test
    void userOfAnotherTenantGets404NotForbidden() throws Exception {
        // 区分"他租户有"与"根本没有"本身就是信息泄漏——能用来探测别的租户的存在性。
        IdentitySeed.Seed mine = seed("ia19", ADMIN_PERMISSIONS);
        IdentitySeed.Seed other = seed("ia20", ADMIN_PERMISSIONS);
        String token = login(mine);

        mockMvc.perform(get("/api/v1/identity/users/" + other.userId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void roleOfAnotherTenantGets404() throws Exception {
        IdentitySeed.Seed mine = seed("ia21", ADMIN_PERMISSIONS);
        IdentitySeed.Seed other = seed("ia22", ADMIN_PERMISSIONS);
        String token = login(mine);
        String otherRoleId = roleIdByCode(other.tenantId(), "ADMIN");

        mockMvc.perform(post("/api/v1/identity/roles/" + otherRoleId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "INACTIVE"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotAssignHotelOfAnotherTenant() throws Exception {
        // 复合外键 fk_user_hotel_scope_hotel 在库层拒绝跨租户挂接。
        // 这里验证的是"拒绝"这件事，而不是它由哪一层拒绝。
        IdentitySeed.Seed mine = seed("ia23", ADMIN_PERMISSIONS);
        IdentitySeed.Seed other = seed("ia24", ADMIN_PERMISSIONS);
        String token = login(mine);

        mockMvc.perform(put("/api/v1/identity/users/" + mine.userId() + "/hotel-scopes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "hotelIds", List.of(other.hotelId())))))
                .andExpect(status().is4xxClientError());

        // 且失败后原范围未被破坏（clear + insert 在同一事务里回滚）
        Assertions.assertEquals(List.of(mine.hotelId()), hotelScopes(token, mine.userId()),
                "跨租户写入失败后应回滚，原范围必须完好");
    }

    // ==================== 辅助 ====================

    private IdentitySeed.Seed seed(String suffix, String[] permissions) {
        return IdentitySeed.seed(organizationService, identityMapper, passwordEncoder, suffix,
                permissions, true);
    }

    private IdentitySeed.Seed seed(String suffix, List<String> permissions) {
        return seed(suffix, permissions.toArray(String[]::new));
    }

    private IdentitySeed.Teammate teammate(IdentitySeed.Seed host, String suffix,
                                           String[] permissions, boolean grantFirstHotel) {
        return IdentitySeed.addTeammate(organizationService, identityMapper, passwordEncoder,
                host, suffix, permissions, grantFirstHotel);
    }

    private String loginTeammate(IdentitySeed.Teammate teammate) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "tenantCode", teammate.tenantCode(),
                "username", teammate.username(),
                "password", IdentitySeed.RAW_PASSWORD));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asString();
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
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asString();
    }

    private void createRole(String token, String code, String name) throws Exception {
        mockMvc.perform(post("/api/v1/identity/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code, "name", name,
                                "permissionCodes", List.of("room:read")))))
                .andExpect(status().isCreated());
    }

    private void changeUserStatus(String token, java.util.UUID userId, String status) throws Exception {
        mockMvc.perform(post("/api/v1/identity/users/" + userId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", status))))
                .andExpect(status().isOk());
    }

    private String roleIdByCode(java.util.UUID tenantId, String code) {
        return identityMapper.findRoleByCode(tenantId, code)
                .orElseThrow(() -> new IllegalStateException("角色不存在：" + code))
                .id().toString();
    }

    private List<java.util.UUID> hotelScopes(String token, java.util.UUID userId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/identity/users/" + userId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body).get("hotelIds");
        List<java.util.UUID> ids = new java.util.ArrayList<>();
        node.forEach(item -> ids.add(java.util.UUID.fromString(item.asString())));
        return ids;
    }
}
