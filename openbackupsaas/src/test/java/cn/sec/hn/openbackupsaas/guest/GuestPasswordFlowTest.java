package cn.sec.hn.openbackupsaas.guest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 住客口令验收（R-20261103-01）：已登录改密 + 忘记密码重置。
 *
 * <h2>这个类在防什么</h2>
 * <ul>
 *   <li><b>会话撤销边界</b>：改密踢其它会话保当前（会话是身份证明之一），
 *       重置撤全部（匿名流程无"当前"）——两条边界写反任何一条都会造成
 *       "改完密码旧设备还在"或"改个密码把自己踢出去"；</li>
 *   <li><b>跨流程挑战防护</b>：登录挑战的码**不能**用于重置（码按用途绑定）——
 *       否则"借登录码钓鱼重置"成为可行攻击；</li>
 *   <li><b>可用性不联动</b>：EMAIL 登录被关停时重置照常可用——
 *       账号恢复不是登录方式，混用会让"临时关停登录"变成"用户永久锁死"；</li>
 *   <li><b>枚举防护</b>：未注册邮箱照发码 + confirm 统一文案；</li>
 *   <li><b>第二因子</b>：改密校验当前口令——被盗 cookie 不等于能换锁。</li>
 * </ul>
 *
 * <p>验证码经真实 SMTP 投递，从 MailPit API 取码；邮件正文含操作类型
 * （"重置登录口令"）——purpose 断言钉死抗钓鱼文案不回退。
 */
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PMS_IT_SMTP", matches = "true")
@TestPropertySource(properties = {
        "pms.guest.ownership.mail.enabled=true",
        "pms.guest.ownership.mail.host=127.0.0.1",
        "pms.guest.ownership.mail.port=55025",
        "pms.guest.ownership.mail.from=it-noreply@pms.test"
})
class GuestPasswordFlowTest extends PostgresIntegrationTest {

    private static final String OLD_PASSWORD = "Guest-Password-2026";
    private static final String NEW_PASSWORD = "Guest-Rotated-2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ==================== 已登录改密 ====================

    @Test
    void changePasswordKeepsCurrentSessionAndRevokesOthers() throws Exception {
        String username = "pwd.change." + UUID.randomUUID();
        String token1 = registerAndLogin(username);
        // "另一台设备"上的会话
        String token2 = login(username, OLD_PASSWORD);

        mockMvc.perform(post("/api/v1/guest/auth/password")
                        .header("Authorization", "Bearer " + token1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", OLD_PASSWORD,
                                "newPassword", NEW_PASSWORD))))
                .andExpect(status().isNoContent());

        // 当前会话保留（改密不该把人自己踢出去）
        mockMvc.perform(get("/api/v1/guest/me")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk());
        // 其它会话被撤销（被盗的旧设备必须掉线）
        mockMvc.perform(get("/api/v1/guest/me")
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isUnauthorized());

        // 旧口令失效、新口令可登录
        mockMvc.perform(post("/api/v1/guest/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", OLD_PASSWORD))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/guest/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", NEW_PASSWORD))))
                .andExpect(status().isOk());
    }

    @Test
    void changePasswordRequiresCorrectCurrentPassword() throws Exception {
        // 第二因子：会话可能是被盗的 cookie，口令比对不能省
        String token = registerAndLogin("pwd.wrong." + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/guest/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", "Totally-Wrong-2026",
                                "newPassword", NEW_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("当前口令不正确"));
    }

    @Test
    void changePasswordRejectsNewPasswordContainingUsername() throws Exception {
        String username = "pwd.policy." + UUID.randomUUID();
        String token = registerAndLogin(username);

        mockMvc.perform(post("/api/v1/guest/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", OLD_PASSWORD,
                                "newPassword", "Secret-" + username + "-2026"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("新口令不能包含登录名"));
    }

    @Test
    void changePasswordFailsForAccountWithoutUsernameCredential() throws Exception {
        // 只剩 EMAIL 凭证的账户没有口令可改——先绑用户名（带口令）才有改密
        String username = "pwd.nouser." + UUID.randomUUID();
        String email = "nouser-" + UUID.randomUUID() + "@pms.test";
        String token = registerAndLogin(username);
        bindEmail(token, email);
        mockMvc.perform(delete("/api/v1/guest/auth/bindings")
                        .header("Authorization", "Bearer " + token)
                        .param("provider", "USERNAME")
                        .param("subject", username))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/guest/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", OLD_PASSWORD,
                                "newPassword", NEW_PASSWORD))))
                .andExpect(status().isConflict());
    }

    // ==================== 忘记密码：重置 ====================

    @Test
    void resetFlowRevokesAllSessionsAndEnablesNewPassword() throws Exception {
        String username = "pwd.reset." + UUID.randomUUID();
        String email = "reset-" + UUID.randomUUID() + "@pms.test";
        String token1 = registerAndLogin(username);
        bindEmail(token1, email);
        String token2 = login(username, OLD_PASSWORD);

        // 发起重置挑战（匿名）
        MvcResult challenge = mockMvc.perform(post("/api/v1/guest/auth/password-reset/challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectMasked").value(email.charAt(0) + "***@pms.test"))
                .andReturn();
        String challengeId = objectMapper.readTree(
                challenge.getResponse().getContentAsString()).get("challengeId").asString();

        // MailPit 取码；邮件正文必须明示操作类型（抗钓鱼文案，purpose 断言）
        String code = awaitCodeFromMailpit(email, "重置登录口令");
        assertNotNull(code, "重置邮件应含操作类型文案");

        // 完成重置（匿名）：204，不自动登录
        mockMvc.perform(post("/api/v1/guest/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId,
                                "code", code,
                                "newPassword", NEW_PASSWORD))))
                .andExpect(status().isNoContent());

        // 全部会话被撤销（匿名流程没有"当前会话"可保留）
        mockMvc.perform(get("/api/v1/guest/me")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/guest/me")
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isUnauthorized());

        // 旧口令失效、新口令可登录（重置后强制重新登录）
        mockMvc.perform(post("/api/v1/guest/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", OLD_PASSWORD))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/guest/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", NEW_PASSWORD))))
                .andExpect(status().isOk());

        // 同一码不能二次使用（一次性消费）
        mockMvc.perform(post("/api/v1/guest/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId,
                                "code", code,
                                "newPassword", "Another-Password-2026"))))
                .andExpect(status().isConflict());
    }

    @Test
    void loginChallengeCodeCannotBeUsedForReset() throws Exception {
        // **跨流程防护**：登录挑战的码只能登录。把登录 challengeId + 正确的码
        // 提交到重置端点必须失败——否则"借登录码钓鱼重置"成为可行攻击。
        String email = "cross-" + UUID.randomUUID() + "@pms.test";
        String username = "pwd.cross." + UUID.randomUUID();
        String token = registerAndLogin(username);
        bindEmail(token, email);

        MvcResult loginChallenge = mockMvc.perform(post("/api/v1/guest/auth/code-login/challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andReturn();
        String loginChallengeId = objectMapper.readTree(
                loginChallenge.getResponse().getContentAsString()).get("challengeId").asString();
        String code = awaitCodeFromMailpit(email, "登录");

        mockMvc.perform(post("/api/v1/guest/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", loginChallengeId,
                                "code", code,
                                "newPassword", NEW_PASSWORD))))
                .andExpect(status().isConflict());

        // 反向同理：重置挑战的码不能用于登录（表分离的对称保证）
        MvcResult resetChallenge = mockMvc.perform(post("/api/v1/guest/auth/password-reset/challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andReturn();
        String resetChallengeId = objectMapper.readTree(
                resetChallenge.getResponse().getContentAsString()).get("challengeId").asString();
        String resetCode = awaitCodeFromMailpit(email, "重置登录口令");
        mockMvc.perform(post("/api/v1/guest/auth/code-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", resetChallengeId,
                                "code", resetCode))))
                .andExpect(status().isConflict());
    }

    @Test
    void resetWorksWithEmailLoginDisabled() throws Exception {
        // **可用性不联动**：EMAIL 登录被运营关停时，重置必须照常可用——
        // 账号恢复不是登录方式；联动等于"临时关停登录"把用户永久锁死。
        jdbcTemplate.update(
                "UPDATE guest_login_policy SET enabled = false WHERE provider = 'EMAIL'");
        try {
            mockMvc.perform(post("/api/v1/guest/auth/password-reset/challenge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("email", "toggle-" + UUID.randomUUID() + "@pms.test"))))
                    .andExpect(status().isOk());
        } finally {
            jdbcTemplate.update(
                    "UPDATE guest_login_policy SET enabled = true WHERE provider = 'EMAIL'");
        }
    }

    @Test
    void unregisteredEmailGetsIdenticalChallengeAndUnifiedFailure() throws Exception {
        String unregistered = "unreg-" + UUID.randomUUID() + "@pms.test";
        MvcResult challenge = mockMvc.perform(post("/api/v1/guest/auth/password-reset/challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", unregistered))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode view = objectMapper.readTree(challenge.getResponse().getContentAsString());
        // 形态与已注册一致（字段集合 + 类型），未注册也真实投递
        assertEquals(java.util.List.of("challengeId", "expiresAt", "subjectMasked"),
                java.util.stream.StreamSupport.stream(view.properties().spliterator(), false)
                        .map(java.util.Map.Entry::getKey).sorted().toList());
        String code = awaitCodeFromMailpit(unregistered, "重置登录口令");

        // 正确的码 + 未注册邮箱 → 401 统一文案（枚举价值归零）
        mockMvc.perform(post("/api/v1/guest/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", view.get("challengeId").asString(),
                                "code", code,
                                "newPassword", NEW_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("验证码或邮箱不正确"));
    }

    @Test
    void fiveWrongCodesExhaustResetChallenge() throws Exception {
        String email = "brute-" + UUID.randomUUID() + "@pms.test";
        String token = registerAndLogin("pwd.brute." + UUID.randomUUID());
        bindEmail(token, email);
        MvcResult challenge = mockMvc.perform(post("/api/v1/guest/auth/password-reset/challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andReturn();
        String challengeId = objectMapper.readTree(
                challenge.getResponse().getContentAsString()).get("challengeId").asString();

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/guest/auth/password-reset")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "challengeId", challengeId,
                                    "code", "111111",
                                    "newPassword", NEW_PASSWORD))))
                    .andExpect(status().isUnauthorized());
        }
        // 第 6 次（含正确码）：已耗尽 → 409
        mockMvc.perform(post("/api/v1/guest/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId,
                                "code", awaitCodeFromMailpit(email, "重置登录口令"),
                                "newPassword", NEW_PASSWORD))))
                .andExpect(status().isConflict());
    }

    // ==================== 工具 ====================

    private String registerAndLogin(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/guest/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", OLD_PASSWORD))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asString();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/guest/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asString();
    }

    /** 走真实绑定流程（挑战-确认）把邮箱挂到当前账户。 */
    private void bindEmail(String token, String email) throws Exception {
        MvcResult challenge = mockMvc.perform(post("/api/v1/guest/auth/bindings/challenge")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", "EMAIL", "subject", email))))
                .andExpect(status().isOk())
                .andReturn();
        String challengeId = objectMapper.readTree(
                challenge.getResponse().getContentAsString()).get("challengeId").asString();
        String code = awaitCodeFromMailpit(email, "绑定登录方式");
        mockMvc.perform(post("/api/v1/guest/auth/bindings/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId, "code", code))))
                .andExpect(status().isOk());
    }

    /** 轮询 MailPit：取发给该地址、正文含指定操作类型的邮件里的 6 位验证码。 */
    private String awaitCodeFromMailpit(String mailbox, String purpose) throws Exception {
        String host = System.getenv().getOrDefault("PMS_SMTP_HOST", "127.0.0.1");
        int uiPort = Integer.parseInt(System.getenv().getOrDefault("PMS_MAILPIT_UI_PORT", "8025"));
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        String lastBody = "";
        while (System.nanoTime() < deadline) {
            HttpRequest list = HttpRequest.newBuilder()
                    .uri(URI.create("http://%s:%d/api/v1/messages?limit=50".formatted(host, uiPort)))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(list, HttpResponse.BodyHandlers.ofString());
            lastBody = response.body();
            for (JsonNode summary : objectMapper.readTree(lastBody).get("messages")) {
                for (JsonNode to : summary.get("To")) {
                    if (mailbox.equals(to.get("Address").asString())) {
                        HttpRequest detail = HttpRequest.newBuilder()
                                .uri(URI.create("http://%s:%d/api/v1/message/%s"
                                        .formatted(host, uiPort, summary.get("ID").asString())))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build();
                        HttpResponse<String> detailResponse =
                                http.send(detail, HttpResponse.BodyHandlers.ofString());
                        String text = objectMapper.readTree(detailResponse.body())
                                .get("Text").asString();
                        if (!text.contains("您正在进行：" + purpose)) {
                            // 同一邮箱可能有多种用途的邮件在飞——只认本次要的那种
                            continue;
                        }
                        java.util.regex.Matcher matcher =
                                java.util.regex.Pattern.compile("\\b(\\d{6})\\b").matcher(text);
                        if (matcher.find()) {
                            return matcher.group(1);
                        }
                    }
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError("MailPit 在 15s 内未收到发给 " + mailbox
                + " 且含「" + purpose + "」的邮件。最后一批：" + lastBody);
    }
}
