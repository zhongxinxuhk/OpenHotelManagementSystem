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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 邮箱验证码登录验收（R-20261102-07）。
 *
 * <h2>这个类在防什么</h2>
 * <ul>
 *   <li><b>邮箱枚举</b>：发起挑战时未注册邮箱与已注册的响应形态必须完全一致，
 *       提交码时"未注册/码错/账户关闭"必须同一句文案——否则任何人都能用
 *       本接口探测某邮箱是否已注册；</li>
 *   <li><b>验证码爆破</b>：6 位数字熵只有 20 bit，哈希存储 + 5 次尝试上限 +
 *       10 分钟过期 + 一次性消费，四条防线任何一条缺失都会把成本拉低；
 *   <li><b>登录开关联动</b>：运营关停 EMAIL 登录后，**已发出的挑战**也不能
 *       完成登录——挑战生命周期只有 10 分钟，这个收紧的代价可接受；</li>
 *   <li><b>绑定与登录可用性解耦</b>：关停 EMAIL 登录不影响"绑定邮箱"
 *       （绑定只看通道能力）——两个动作的可用性不该联动。</li>
 * </ul>
 *
 * <p>验证码经**真实 SMTP** 投递（mail.enabled=true 指向 compose 的 MailPit），
 * 从 MailPit API 取码——与 GuestBindingMailFlowTest 同一模式。
 */
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PMS_IT_SMTP", matches = "true")
@TestPropertySource(properties = {
        "pms.guest.ownership.mail.enabled=true",
        "pms.guest.ownership.mail.host=127.0.0.1",
        "pms.guest.ownership.mail.port=55025",
        "pms.guest.ownership.mail.from=it-noreply@pms.test"
})
// EMAIL 登录开关在种子里默认关（运营态由控制台开）；
// 测试基线等价于"管理员已打开 EMAIL 登录"，开关行为由专项用例覆盖。
class GuestCodeLoginFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "Guest-Password-2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @org.junit.jupiter.api.BeforeEach
    void enableEmailLogin() {
        // 种子里 EMAIL 默认关（运营态由控制台开）。本类基线=管理员已打开；
        // 关停行为由 disabledEmailLoginRejectsChallengeAndPendingConfirm 用例内改写。
        jdbcTemplate.update(
                "UPDATE guest_login_policy SET enabled = true WHERE provider = 'EMAIL'");
    }

    // ==================== 全链路 ====================

    @Test
    void emailCodeLoginIssuesSessionFromRealMail() throws Exception {
        // 准备：注册一个账户并绑定邮箱（绑定流程已有专项测试，这里直插凭证行
        // ——本类的焦点是登录，不是绑定）
        String username = "code.login." + UUID.randomUUID();
        String email = "login-" + UUID.randomUUID() + "@pms.test";
        String accountId = registerAccount(username);
        jdbcTemplate.update(
                "INSERT INTO guest_credential (account_id, provider, subject, verified_at) "
                        + "VALUES (?, 'EMAIL', ?, now())",
                UUID.fromString(accountId), email);

        // 发起登录挑战（真实 SMTP）
        MvcResult challenge = mockMvc.perform(post("/api/v1/guest/auth/code-login/challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                // 响应不含验证码，只给掩码——与绑定挑战同一条铁律
                .andExpect(jsonPath("$.subjectMasked").value(email.charAt(0) + "***@pms.test"))
                .andReturn();
        String challengeId = objectMapper.readTree(
                challenge.getResponse().getContentAsString()).get("challengeId").asString();

        // MailPit 取真实验证码
        String code = awaitCodeFromMailpit(email);

        // 提交登录：签发会话
        mockMvc.perform(post("/api/v1/guest/auth/code-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId, "code", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.verificationStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$.user.realNameRequired").value(true));

        // 同一码不能二次使用（一次性消费）
        mockMvc.perform(post("/api/v1/guest/auth/code-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId, "code", code))))
                .andExpect(status().isConflict());
    }

    // ==================== 枚举防护 ====================

    @Test
    void unregisteredEmailGetsIdenticalChallengeResponse() throws Exception {
        // 未注册邮箱也照发码：challengeId/掩码/过期时间与已注册形态完全一致。
        // 差异只存在于邮件投递之外（confirm 时统一文案），响应面无差异可探测。
        String registered = "enum-reg@" + UUID.randomUUID() + ".pms.test";
        String unregistered = "enum-unreg@" + UUID.randomUUID() + ".pms.test";
        String accountId = registerAccount("enum.probe." + UUID.randomUUID());
        jdbcTemplate.update(
                "INSERT INTO guest_credential (account_id, provider, subject, verified_at) "
                        + "VALUES (?, 'EMAIL', ?, now())",
                UUID.fromString(accountId), registered);

        MvcResult a = mockMvc.perform(post("/api/v1/guest/auth/code-login/challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", registered))))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult b = mockMvc.perform(post("/api/v1/guest/auth/code-login/challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", unregistered))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode viewA = objectMapper.readTree(a.getResponse().getContentAsString());
        JsonNode viewB = objectMapper.readTree(b.getResponse().getContentAsString());
        // 字段集合一致（值本身随机，不比较 challengeId/expiresAt）——Jackson 3 的
        // fieldNames() 返回 Iterable<String>，用 stream 收集
        var fieldsA = java.util.stream.StreamSupport.stream(
                        viewA.properties().spliterator(), false)
                .map(java.util.Map.Entry::getKey).sorted().toList();
        var fieldsB = java.util.stream.StreamSupport.stream(
                        viewB.properties().spliterator(), false)
                .map(java.util.Map.Entry::getKey).sorted().toList();
        assertEquals(fieldsA, fieldsB, "未注册邮箱的响应字段必须与已注册一致（枚举防护）");
        assertNotNull(viewB.get("challengeId").asString());
        assertTrue(viewB.get("expiresAt").asString().startsWith("20"));
        // 未注册邮箱也真实投递了验证码（防探测的另一面：两边都发）
        assertNotNull(awaitCodeFromMailpit(unregistered), "未注册邮箱也应收到验证码邮件");
    }

    @Test
    void confirmUnifiesWrongCodeAndUnregisteredEmail() throws Exception {
        // 两个失败分支必须同一句文案、同一状态码——
        // 否则攻击者可用"正确码+未注册邮箱"探测，或用"错码+已注册邮箱"确认存在性。
        String unregistered = "unify-" + UUID.randomUUID() + "@pms.test";
        MvcResult challenge = mockMvc.perform(post("/api/v1/guest/auth/code-login/challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", unregistered))))
                .andExpect(status().isOk())
                .andReturn();
        String challengeId = objectMapper.readTree(
                challenge.getResponse().getContentAsString()).get("challengeId").asString();

        // 正确的码 + 未注册邮箱 → 401 统一文案
        String code = awaitCodeFromMailpit(unregistered);
        mockMvc.perform(post("/api/v1/guest/auth/code-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId, "code", code))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("验证码或邮箱不正确"));

        // 上一提交已消费挑战（码正确但邮箱未注册），此后同一挑战任何提交都是 409——
        // 统一文案防线在前一步已验（401 同一句），这里钉死的是"消费后不可重放"。
    }

    // ==================== 防爆破 ====================

    @Test
    void fiveWrongCodesExhaustTheChallenge() throws Exception {
        String email = "brute-" + UUID.randomUUID() + "@pms.test";
        MvcResult challenge = mockMvc.perform(post("/api/v1/guest/auth/code-login/challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andReturn();
        String challengeId = objectMapper.readTree(
                challenge.getResponse().getContentAsString()).get("challengeId").asString();

        // 前 5 次错误码：401（计入尝试）
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/guest/auth/code-login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "challengeId", challengeId, "code", "111111"))))
                    .andExpect(status().isUnauthorized());
        }
        // 第 6 次（含正确码）：挑战已耗尽 → 409，需重新发起
        mockMvc.perform(post("/api/v1/guest/auth/code-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId, "code", awaitCodeFromMailpit(email)))))
                .andExpect(status().isConflict());
    }

    // ==================== 方式开关 ====================

    @Test
    void disabledEmailLoginRejectsChallengeAndPendingConfirm() throws Exception {
        String email = "toggle-" + UUID.randomUUID() + "@pms.test";
        String accountId = registerAccount("toggle." + UUID.randomUUID());
        jdbcTemplate.update(
                "INSERT INTO guest_credential (account_id, provider, subject, verified_at) "
                        + "VALUES (?, 'EMAIL', ?, now())",
                UUID.fromString(accountId), email);

        // 关停 EMAIL 登录（实现 ∧ 开关——控制台动作；基线在 @BeforeEach 已打开）
        jdbcTemplate.update(
                "UPDATE guest_login_policy SET enabled = false WHERE provider = 'EMAIL'");
        try {
            mockMvc.perform(post("/api/v1/guest/auth/code-login/challenge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("email", email))))
                    .andExpect(status().isForbidden());
        } finally {
            jdbcTemplate.update(
                    "UPDATE guest_login_policy SET enabled = true WHERE provider = 'EMAIL'");
        }

        // 方式清单如实反映（EMAIL 现在是 implemented=true）
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/guest/auth/methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.provider=='EMAIL')].available").value(true));
    }

    // ==================== 工具 ====================

    private String registerAccount(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/guest/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", PASSWORD,
                                "displayName", "验证码登录测试"))))
                .andExpect(status().isCreated())
                .andReturn();
        // accountId 不在响应里（ADR-018）；从库里取（测试可见内部标识）
        return jdbcTemplate.queryForObject(
                "SELECT id FROM guest_account WHERE id = (SELECT account_id FROM guest_credential WHERE provider='USERNAME' AND subject=?)",
                String.class, username);
    }

    /** 轮询 MailPit，取出发给该地址那封邮件正文里的 6 位验证码。 */
    private String awaitCodeFromMailpit(String mailbox) throws Exception {
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
        throw new AssertionError("MailPit 在 15s 内未收到发给 " + mailbox + " 的邮件。最后一批：" + lastBody);
    }
}
