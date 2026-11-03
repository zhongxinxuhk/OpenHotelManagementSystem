package cn.sec.hn.openbackupsaas.guest;

import cn.sec.hn.openbackupsaas.guest.domain.GuestAccount;
import cn.sec.hn.openbackupsaas.guest.domain.GuestProvider;
import cn.sec.hn.openbackupsaas.guest.service.GuestAccountService;
import cn.sec.hn.openbackupsaas.platform.service.PlatformAuthenticationService;
import cn.sec.hn.openbackupsaas.support.IdentitySeed;
import cn.sec.hn.openbackupsaas.support.PlatformSeed;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 住客用户名+密码登录与方式开关验收（ADR-019 §2.9，R-20261101-32）。
 *
 * <h2>这个类在防什么</h2>
 * <ul>
 *   <li><b>账号枚举</b>：登录失败的三个分支（用户名不存在/密码错/账户已关闭）
 *       若文案不同，登录接口就成了"这个用户名存不存在"的探测器。</li>
 *   <li><b>开关与实现混为一谈</b>：管理员把 PHONE 打开就以为在接通道——
 *       可用性必须是"实现 ∧ 开关"双条件，开关对未实现方式无效。</li>
 *   <li><b>注册即登录</b>：注册响应若不含会话，客户端要做第二次登录才能进入，
 *       多一次密码传输也多一次出错机会。</li>
 *   <li><b>实名前置丢失</b>：注册响应必须明确"还需要实名"（realNameRequired=true），
 *       否则客户端会让未实名的人直接去订房。</li>
 * </ul>
 */
@AutoConfigureMockMvc
class GuestAuthFlowTest extends PostgresIntegrationTest {

    private static final String USERNAME = "traveller.one";
    private static final String PASSWORD = "Guest-Password-2026";
    private static final String NEW_PASSWORD = "Guest-Rotated-2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GuestAccountService guestService;

    @Autowired
    private PlatformAuthenticationService platformAuthService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 注册 → 登录 → 登出 ====================

    @Test
    void registerReturnsSessionAndFlagsRealNameRequired() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/guest/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", USERNAME,
                                "password", PASSWORD,
                                "displayName", "旅客一"))))
                .andExpect(status().isCreated())
                // 注册即登录：客户端不需要第二次密码传输
                .andExpect(jsonPath("$.token").isNotEmpty())
                // 响应不含主身份 id（ADR-018：客户端不感知内部标识）
                .andExpect(jsonPath("$.user.displayName").value("旅客一"))
                .andExpect(jsonPath("$.user.verificationStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$.user.realNameRequired").value(true))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("token").asString();

        // 用注册会话直接读 me
        mockMvc.perform(get("/api/v1/guest/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("旅客一"));
    }

    @Test
    void loginWithRegisteredCredentialsAndLogout() throws Exception {
        register(USERNAME, PASSWORD);

        MvcResult result = mockMvc.perform(post("/api/v1/guest/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "  Traveller.One  ",
                                "password", PASSWORD))))
                // 大小写与空白规范化：Traveller.One 与 traveller.one 是同一个账号
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asString();

        mockMvc.perform(post("/api/v1/guest/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 登出后该会话失效
        mockMvc.perform(get("/api/v1/guest/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateUsernameIsRejected() throws Exception {
        register(USERNAME, PASSWORD);

        mockMvc.perform(post("/api/v1/guest/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", USERNAME,
                                "password", "Another-Password-2026"))))
                .andExpect(status().isConflict());
    }

    @Test
    void weakPasswordIsRejected() throws Exception {
        // 口令策略与员工账号同源：12 位下限、不得包含用户名
        mockMvc.perform(post("/api/v1/guest/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "weak-pw",
                                "password", "short"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/guest/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "selfref",
                                "password", "selfref-Password-2026"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginFailuresAreIndistinguishable() throws Exception {
        register(USERNAME, PASSWORD);
        register("closed.one", PASSWORD);
        jdbcTemplate.update("UPDATE guest_account SET status = 'CLOSED' WHERE id = ?",
                accountIdOf("closed.one"));

        String[] usernames = {
                USERNAME,                       // 密码错
                "nobody.here",                  // 用户名不存在
                "closed.one",                   // 账户已关闭（密码正确）
        };
        for (int i = 0; i < usernames.length; i++) {
            MvcResult result = mockMvc.perform(post("/api/v1/guest/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", usernames[i],
                                    "password", "Wrong-Guess-2026"))))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            Assertions.assertTrue(body.contains("用户名或密码不正确"),
                    "第 " + (i + 1) + " 个分支的文案必须与其它分支一致：" + body);
            // 不泄露具体原因
            Assertions.assertFalse(body.contains("已关闭") || body.contains("不存在"),
                    "登录失败不得区分原因（账号枚举防护）：" + body);
        }
    }

    // ==================== 登录方式清单与开关 ====================

    @Test
    void methodsListIsAnonymousAndMarksAvailability() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/guest/auth/methods"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        // USERNAME：已实现且默认开启 → 可用
        Assertions.assertTrue(body.contains("\"provider\":\"USERNAME\""));
        Assertions.assertTrue(body.contains("\"available\":true"),
                "USERNAME 应该可用（种子默认开启）：" + body);
        // PHONE：开关关闭且未实现
        Assertions.assertTrue(body.contains("\"provider\":\"PHONE\""));
    }

    @Test
    void disablingUsernameBlocksLoginImmediately() throws Exception {
        register(USERNAME, PASSWORD);
        String adminToken = loginPlatform("policy-admin", "guest.authpolicy.write");

        try {
            mockMvc.perform(put("/api/v1/platform/guest-login-policy/USERNAME")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false));

            // 关闭后：登录请求直接"未开放"（403），而不是 401——
            // 此时比对密码毫无意义，也不能让用户以为是密码错了
            mockMvc.perform(post("/api/v1/guest/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", USERNAME,
                                    "password", PASSWORD))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("登录方式未开放"));
        } finally {
            // 策略表在清理器保留清单里，必须自行恢复，否则污染其它用例
            mockMvc.perform(put("/api/v1/platform/guest-login-policy/USERNAME")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                    .andExpect(status().isOk());
        }

        // 恢复后重新可登录
        mockMvc.perform(post("/api/v1/guest/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", USERNAME,
                                "password", PASSWORD))))
                .andExpect(status().isOk());
    }

    @Test
    void enablingUnimplementedMethodDoesNotMakeItAvailable() throws Exception {
        // 需求方口径的核心验证：开关只是开关。PHONE 没接验证码通道，
        // 打开开关后对外仍然是"未开放"——"接通"永远是一次代码发布。
        String adminToken = loginPlatform("policy-admin2", "guest.authpolicy.write");

        try {
            mockMvc.perform(put("/api/v1/platform/guest-login-policy/PHONE")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.implemented").value(false))
                    .andExpect(jsonPath("$.available").value(false));

            // 客户端看到的清单里 PHONE 仍不可用
            mockMvc.perform(get("/api/v1/guest/auth/methods"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.provider == 'PHONE')].available")
                            .value(org.hamcrest.Matchers.contains(false)));
        } finally {
            mockMvc.perform(put("/api/v1/platform/guest-login-policy/PHONE")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void policyChangeRequiresTheWriteAction() throws Exception {
        String readerToken = loginPlatform("policy-reader", "guest.authpolicy.read");

        mockMvc.perform(get("/api/v1/platform/guest-login-policy")
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/platform/guest-login-policy/USERNAME")
                        .header("Authorization", "Bearer " + readerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isForbidden());
    }

    // ==================== 夹具 ====================

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/v1/guest/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isCreated());
    }

    private java.util.UUID accountIdOf(String username) {
        return java.util.UUID.fromString(jdbcTemplate.queryForObject(
                "SELECT account_id FROM guest_credential WHERE provider = 'USERNAME' AND subject = ?",
                String.class, username));
    }

    private String loginPlatform(String username, String... actions) {
        PlatformSeed.create(jdbcTemplate, passwordEncoder, username, "平台 " + username, actions);
        return platformAuthService.login(username, IdentitySeed.RAW_PASSWORD).token();
    }
}
