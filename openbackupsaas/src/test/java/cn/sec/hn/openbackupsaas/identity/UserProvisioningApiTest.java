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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 员工开通与授权闭环验收（S3-02 续，R-20261101-27）。
 *
 * <h2>这个类在防什么</h2>
 * 开通员工这条链路上，出错的形态几乎都是"看起来成功了"：
 * <ul>
 *   <li>初始口令如果只是"发出去"，管理员就**长期持有**员工账号的可用凭据，
 *       日志上分不出到底是谁操作的 —— 必须强制改密，且改完旧口令立即失效；</li>
 *   <li>待改密如果只在前端跳转，改个接口就能绕过 —— 必须服务端拦，且拦得住写操作；</li>
 *   <li>角色替换如果退化成"只能加不能减"，降权静默失效，
 *       界面显示已取消勾选、权限其实还在；</li>
 *   <li>自保护如果只看"自己改完还剩什么"，双管理员场景会被误拦；
 *       只看"不能变空"又会漏掉"降到一个没有 identity:write 的角色"，照样锁死。</li>
 * </ul>
 * 这些用眼睛看代码都看不出来，只能靠行为断言。
 */
@AutoConfigureMockMvc
class UserProvisioningApiTest extends PostgresIntegrationTest {

    private static final String[] ADMIN_PERMISSIONS = {
            "identity:read", "identity:write", "org:read"
    };

    /** 满足口令策略且不含任何测试登录名。 */
    private static final String NEW_PASSWORD = "Front-Desk-2026-A";

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

    // ==================== 开通：初始口令 ====================

    @Test
    void createdUserCanLogInWithInitialPassword() throws Exception {
        IdentitySeed.Seed host = seed("up1");
        String token = login(host);
        String clerkRoleId = createRole(token, "CLERK_A", "前台接待", List.of("room:read"));

        JsonNode created = createUser(token, "clerk01", "前台小王",
                List.of(clerkRoleId), List.of(host.hotelId()));

        String initialPassword = created.get("initialPassword").asString();
        Assertions.assertEquals(16, initialPassword.length(),
                "初始口令长度由生成器固定；长度变化说明生成规则被改过");
        Assertions.assertEquals("clerk01", created.get("user").get("username").asString());
        Assertions.assertEquals("ACTIVE", created.get("user").get("status").asString());
        Assertions.assertEquals(1, created.get("roles").size());
        Assertions.assertEquals(host.hotelId().toString(),
                created.get("hotelIds").get(0).asString());

        // 核心断言：这个口令真的能登录。发出去却登不上的口令等于没开通。
        String clerkToken = loginRaw(host.tenantCode(), "clerk01", initialPassword);
        Assertions.assertFalse(clerkToken.isBlank());
    }

    @Test
    void initialPasswordIsNotReadableAgain() throws Exception {
        // 口令只在创建响应里出现一次。若"再查一次"能拿到，管理员就等于长期持有该账号。
        IdentitySeed.Seed host = seed("up2");
        String token = login(host);
        String clerkRoleId = createRole(token, "CLERK_B", "前台", List.of("room:read"));

        String clerkId = createUser(token, "clerk02", "前台小李",
                List.of(clerkRoleId), List.of()).get("user").get("id").asString();

        String detail = mockMvc.perform(get("/api/v1/identity/users/" + clerkId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Assertions.assertFalse(detail.contains("initialPassword"), "详情里出现了 initialPassword 字段");
        Assertions.assertFalse(detail.contains("passwordHash"), "详情里出现了 passwordHash 字段");
        Assertions.assertFalse(detail.contains("$2a$"), "详情里出现了 BCrypt 哈希");
    }

    @Test
    void createdUserMustChangePasswordBeforeAnyBusinessCall() throws Exception {
        // 待改密必须在**服务端**拦：写成前端跳转的话，改个请求就绕过去了。
        IdentitySeed.Seed host = seed("up3");
        String adminToken = login(host);
        // 角色刻意给足权限：下面被拒时只可能是"待改密"拦的，不是权限不足。
        // 否则用例会在拦截器失效时照样通过，测不出任何东西。
        String clerkRoleId = createRole(adminToken, "CLERK_C", "前台",
                List.of("room:read", "room:write", "org:read"));

        JsonNode created = createUser(adminToken, "clerk03", "前台小张",
                List.of(clerkRoleId), List.of(host.hotelId()));
        String clerkToken = loginRaw(host.tenantCode(), "clerk03",
                created.get("initialPassword").asString());

        // 登录响应要如实告诉前端"还得先改密"，否则前端只能靠 403 去猜
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + clerkToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        // 业务接口被拒，且带可识别错误码（前端据此跳改密页，而不是当成"没权限"）
        mockMvc.perform(get("/api/v1/hotels")
                        .header("Authorization", "Bearer " + clerkToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        // 写操作同样拦住——只挡 GET 等于没挡
        mockMvc.perform(post("/api/v1/hotels/" + host.hotelId() + "/room-types")
                        .header("Authorization", "Bearer " + clerkToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "TRY", "name", "绕过试试",
                                "capacityAdults", 2, "capacityChildren", 0, "bedType", "TWIN"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    void changingPasswordLiftsRestrictionAndInvalidatesInitialOne() throws Exception {
        // 三件事一次验完：改密后立刻可以干业务、旧口令失效、标记按请求实时读取。
        IdentitySeed.Seed host = seed("up4");
        String adminToken = login(host);
        String clerkRoleId = createRole(adminToken, "CLERK_D", "前台",
                List.of("room:read", "room:write", "org:read"));

        JsonNode created = createUser(adminToken, "clerk04", "前台小赵",
                List.of(clerkRoleId), List.of(host.hotelId()));
        String initialPassword = created.get("initialPassword").asString();
        String clerkToken = loginRaw(host.tenantCode(), "clerk04", initialPassword);

        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + clerkToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", initialPassword,
                                "newPassword", NEW_PASSWORD))))
                .andExpect(status().isNoContent());

        // 标记是每次请求从库里读的，所以**同一个令牌**的下一次请求就已经放行，
        // 不需要重新登录。若这里失败，说明标记被缓存进了令牌。
        mockMvc.perform(get("/api/v1/hotels")
                        .header("Authorization", "Bearer " + clerkToken))
                .andExpect(status().isOk());

        // 管理员手上那份副本作废：旧口令登不进来
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(host.tenantCode(), "clerk04", initialPassword)))
                .andExpect(status().isUnauthorized());

        // 自己的新口令可以
        Assertions.assertFalse(loginRaw(host.tenantCode(), "clerk04", NEW_PASSWORD).isBlank());
    }

    // ==================== 开通：参数校验 ====================

    @Test
    void duplicateUsernameGets409() throws Exception {
        IdentitySeed.Seed host = seed("up5");
        String token = login(host);
        String roleId = createRole(token, "CLERK_E", "前台", List.of("room:read"));
        createUser(token, "clerk05", "前台", List.of(roleId), List.of());

        mockMvc.perform(post("/api/v1/identity/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "clerk05", "displayName", "重名",
                                "roleIds", List.of(roleId)))))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidUsernameFormatGets400() throws Exception {
        // 登录名会进日志与审计，格式必须统一；大小写混用还会造出"看起来是同一个人"的两个账号。
        IdentitySeed.Seed host = seed("up6");
        String token = login(host);
        String roleId = createRole(token, "CLERK_F", "前台", List.of("room:read"));

        mockMvc.perform(post("/api/v1/identity/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "Bad Name!", "displayName", "格式错",
                                "roleIds", List.of(roleId)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("登录名格式")));
    }

    @Test
    void usernameIsNormalizedToLowerCase() throws Exception {
        // 大小写归一后，"Zhang.San" 与 "zhang.san" 是同一个账号，不会各建一个。
        IdentitySeed.Seed host = seed("up7");
        String token = login(host);
        String roleId = createRole(token, "CLERK_G", "前台", List.of("room:read"));

        JsonNode created = createUser(token, "Clerk.MixedCase", "前台",
                List.of(roleId), List.of());
        Assertions.assertEquals("clerk.mixedcase",
                created.get("user").get("username").asString());
    }

    @Test
    void noRolesIsRejected() throws Exception {
        // 无角色的账号能登录、能改密，却什么都做不了 —— 它就是一个"活的凭据"。
        IdentitySeed.Seed host = seed("up8");
        String token = login(host);

        mockMvc.perform(post("/api/v1/identity/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "norole01", "displayName", "无角色",
                                "roleIds", List.of()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void inactiveRoleCannotBeAssigned() throws Exception {
        // 分配一个停用角色等于什么都没给，但界面上看起来"已经授权完成"了。
        IdentitySeed.Seed host = seed("up9");
        String token = login(host);
        String roleId = createRole(token, "CLERK_H", "前台", List.of("room:read"));

        mockMvc.perform(post("/api/v1/identity/roles/" + roleId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "INACTIVE"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/identity/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "inactive01", "displayName", "前台",
                                "roleIds", List.of(roleId)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("已停用")));
    }

    @Test
    void cannotAssignRoleOrHotelOfAnotherTenant() throws Exception {
        // 跨租户由复合外键兜底也能挡住，但那会变成 500（DataIntegrityViolation），
        // 调用方看到"服务器错误"而不是"你给了别的租户的角色"。
        IdentitySeed.Seed mine = seed("up10");
        IdentitySeed.Seed other = seed("up11");
        String token = login(mine);
        String otherRoleId = identityMapper.findRoleByCode(other.tenantId(), "ADMIN")
                .orElseThrow().id().toString();

        mockMvc.perform(post("/api/v1/identity/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "crossrole01", "displayName", "越权",
                                "roleIds", List.of(otherRoleId)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("角色不存在或不属于当前租户")));

        String ownRoleId = createRole(token, "CLERK_I", "前台", List.of("room:read"));
        mockMvc.perform(post("/api/v1/identity/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "crosshotel01", "displayName", "越权",
                                "roleIds", List.of(ownRoleId),
                                "hotelIds", List.of(other.hotelId())))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("门店不存在或不属于当前租户")));
    }

    // ==================== 角色分配 ====================

    @Test
    void roleAssignmentIsFullReplacementNotAppend() throws Exception {
        // 降权必须真的生效。只 assignRole（ON CONFLICT DO NOTHING）等于只能加不能减，
        // 界面显示已取消勾选、权限其实还在。
        IdentitySeed.Seed host = seed("up12");
        String token = login(host);
        String frontDesk = createRole(token, "CLERK_J", "前台", List.of("room:read", "reservation:read"));
        String housekeeping = createRole(token, "HK_J", "客房", List.of("room:read"));

        String clerkId = createUser(token, "clerk12", "前台",
                List.of(frontDesk), List.of(host.hotelId())).get("user").get("id").asString();

        mockMvc.perform(put("/api/v1/identity/users/" + clerkId + "/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roleIds", List.of(housekeeping)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0].code").value("HK_J"));

        // 关键：前台角色**真的没了**，不是"两个都在"
        String detail = mockMvc.perform(get("/api/v1/identity/users/" + clerkId)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        Assertions.assertFalse(detail.contains("CLERK_J"),
                "全量替换后不应保留原角色；若保留说明实现退化成 upsert");
    }

    @Test
    void roleChangeTakesEffectOnNextRequestWithoutRelogin() throws Exception {
        // 权限是每请求实时查库的，所以换角色不需要失效广播、也不需要重新登录。
        IdentitySeed.Seed host = seed("up13");
        String token = login(host);
        String readOnly = createRole(token, "RO_J", "只读", List.of("room:read"));
        String reserved = createRole(token, "RSV_J", "预订员", List.of("reservation:read"));

        JsonNode created = createUser(token, "clerk13", "前台",
                List.of(readOnly), List.of(host.hotelId()));
        String clerkId = created.get("user").get("id").asString();
        // 初始口令只能从创建响应里拿到一次，这里取下来供登录与改密使用。
        String initialPassword = created.get("initialPassword").asString();
        String clerkToken = loginRaw(host.tenantCode(), "clerk13", initialPassword);

        // 先改密解除限制，才能观察到业务权限
        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + clerkToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", initialPassword,
                                "newPassword", NEW_PASSWORD))))
                .andExpect(status().isNoContent());

        // 换角色之前：没有 reservation:read，查订单被拒（403）
        mockMvc.perform(get("/api/v1/hotels/" + host.hotelId() + "/reservations")
                        .header("Authorization", "Bearer " + clerkToken))
                .andExpect(status().isForbidden());

        // 换成有 reservation:write 的角色
        mockMvc.perform(put("/api/v1/identity/users/" + clerkId + "/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roleIds", List.of(reserved)))))
                .andExpect(status().isOk());

        // 同一个令牌，下一次请求就已经带上新权限：不再 403。
        // 用 GET 而不是 POST：POST 的空 body 会先被参数校验拒成 400，
        // 根本走不到鉴权，那样测不出"权限是否已更新"。
        mockMvc.perform(get("/api/v1/hotels/" + host.hotelId() + "/reservations")
                        .header("Authorization", "Bearer " + clerkToken))
                .andExpect(status().isOk());
    }

    // ==================== 自保护 ====================

    @Test
    void cannotStripOwnRolesWhenNoOtherAdminRemains() throws Exception {
        // 与"不能停用自己"同源：把自己降到没有任何角色 = 没有任何权限，
        // 而系统里没有第二个入口能恢复（平台运维身份 A-05 仍 BLOCKED）。
        IdentitySeed.Seed host = seed("up14");
        String token = login(host);

        mockMvc.perform(put("/api/v1/identity/users/" + host.userId() + "/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("roleIds", List.of()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("不能撤销自己的员工与角色管理权限")));

        // 真的没被改：原角色还在，仍然能管身份
        mockMvc.perform(get("/api/v1/identity/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void cannotDemoteSelfToRoleWithoutIdentityWriteWhenSolo() throws Exception {
        // 只拦"变成空"是不够的：降到一个只读角色，同样再也改不回来。
        IdentitySeed.Seed host = seed("up15");
        String token = login(host);
        String readOnly = createRole(token, "RO_K", "只读", List.of("room:read"));

        mockMvc.perform(put("/api/v1/identity/users/" + host.userId() + "/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roleIds", List.of(readOnly)))))
                .andExpect(status().isConflict());
    }

    @Test
    void selfDemotionIsAllowedWhenAnotherAdminExists() throws Exception {
        // 反过来也要成立：租户里还有人能管身份时，主动降权是合法运维动作。
        // 一律拦住只会逼出一个更糟的做法——直接改库。
        IdentitySeed.Seed host = seed("up16");
        IdentitySeed.Teammate backup = IdentitySeed.addTeammate(organizationService,
                identityMapper, passwordEncoder, host, "bk",
                new String[]{"identity:read", "identity:write"}, true);
        String token = login(host);
        String backupToken = loginRaw(backup.tenantCode(), backup.username(), IdentitySeed.RAW_PASSWORD);
        String readOnly = createRole(token, "RO_L", "只读", List.of("room:read"));

        mockMvc.perform(put("/api/v1/identity/users/" + host.userId() + "/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roleIds", List.of(readOnly)))))
                .andExpect(status().isOk());

        // 用**备份管理员**的身份确认改动落地（此时 host 已没有 identity:read）
        mockMvc.perform(get("/api/v1/identity/users/" + host.userId())
                        .header("Authorization", "Bearer " + backupToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0].code").value("RO_L"));
    }

    // ==================== 辅助 ====================

    private IdentitySeed.Seed seed(String suffix) {
        return IdentitySeed.seed(organizationService, identityMapper, passwordEncoder, suffix,
                ADMIN_PERMISSIONS, true);
    }

    private String login(IdentitySeed.Seed seed) throws Exception {
        return loginRaw(seed.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD);
    }

    private String loginRaw(String tenantCode, String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(tenantCode, username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asString();
    }

    private String loginBody(String tenantCode, String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "tenantCode", tenantCode, "username", username, "password", password));
    }

    private String createRole(String token, String code, String name,
                             List<String> permissionCodes) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/identity/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code, "name", name,
                                "permissionCodes", permissionCodes))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
    }

    private JsonNode createUser(String token, String username, String displayName,
                                List<String> roleIds, List<UUID> hotelIds) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("displayName", displayName);
        payload.put("roleIds", roleIds);
        payload.put("hotelIds", hotelIds);
        MvcResult result = mockMvc.perform(post("/api/v1/identity/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 初始口令取不回来，只能在创建时记下；这个辅助方法用于用例内二次取用。 */
    private String newInitialPassword(String token, String clerkId) throws Exception {
        String stored = INITIAL_PASSWORDS.get(clerkId);
        if (stored == null) {
            throw new IllegalStateException("用例没有记录该员工的初始口令：" + clerkId);
        }
        return stored;
    }

    /** 创建时把初始口令记下来——它在真实系统里只能被操作者抄一次。 */
    private final Map<String, String> INITIAL_PASSWORDS = new HashMap<>();
}
