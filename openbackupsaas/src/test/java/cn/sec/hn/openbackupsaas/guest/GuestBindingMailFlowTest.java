package cn.sec.hn.openbackupsaas.guest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 邮箱归属验证的<b>全链路</b>验收（真实 PostgreSQL + 真实 SMTP/MailPit，R-20261102-01）。
 *
 * <h2>与既有测试的分工</h2>
 * <ul>
 *   <li>{@code GuestBindingFlowTest}：挑战/确认/防爆破的数据库行为（dev 信箱取码）；</li>
 *   <li>{@code MailChallengeSenderSmokeTest}：发送器层的真实 SMTP 会话；</li>
 *   <li><b>本类</b>：把两层连起来——HTTP 发起 EMAIL 挑战后，验证码必须真的出现在
 *       MailPit 收到的邮件里，用它 confirm 才能绑定成功。任何一环接错
 *       （路由接反、EMAIL 被信箱遮蔽、邮件正文缺码）都过不了这条链。</li>
 * </ul>
 *
 * <p>同时打开 {@code mail.enabled} 与 integration 档案默认的 {@code dev-mailbox}：
 * 这正是"真实通道优先、信箱兜底"的共存形态——EMAIL 必须走 SMTP 而不是静默落信箱，
 * 本类的取码只认 MailPit，落信箱即失败。
 *
 * <p>默认跳过（{@code PMS_IT_SMTP=true} 才跑，CI 由 compose 的 MailPit 提供）；
 * 本地先 {@code docker compose up -d --wait}（deploy/compose）。
 */
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PMS_IT_SMTP", matches = "true")
@TestPropertySource(properties = {
        "pms.guest.ownership.mail.enabled=true",
        "pms.guest.ownership.mail.host=127.0.0.1",
        "pms.guest.ownership.mail.port=55025",
        "pms.guest.ownership.mail.from=it-noreply@pms.test"
})
class GuestBindingMailFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "Guest-Password-2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void emailChallengeDeliversRealMailAndConfirmsBinding() throws Exception {
        String token = registerAndLogin("mail.flow.one");
        // 收件地址唯一化：MailPit 里可能残留此前运行的邮件，靠地址过滤出"这一次"。
        String mailbox = "it-" + UUID.randomUUID() + "@pms.test";

        MvcResult challenge = mockMvc.perform(post("/api/v1/guest/auth/bindings/challenge")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", "EMAIL", "subject", mailbox))))
                .andExpect(status().isOk())
                // 响应仍然不含验证码、只给掩码——接通真实通道不改变这条铁律
                .andExpect(jsonPath("$.subjectMasked").value(mailbox.charAt(0) + "***@pms.test"))
                .andReturn();
        String challengeId = objectMapper.readTree(
                challenge.getResponse().getContentAsString()).get("challengeId").asString();

        String code = awaitCodeFromMailpit(mailbox);

        mockMvc.perform(post("/api/v1/guest/auth/bindings/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId, "code", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].provider").value("EMAIL"))
                .andExpect(jsonPath("$[0].subject").value(mailbox));

        // 绑定后的凭证列表对本人可见完整标识
        mockMvc.perform(get("/api/v1/guest/auth/bindings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.provider=='EMAIL')].subject").value(mailbox));
    }

    // ==================== 工具 ====================

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
                        assertTrue(matcher.find(), "邮件正文应含 6 位验证码：" + text);
                        return matcher.group(1);
                    }
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError("MailPit 在 15s 内未收到发给 " + mailbox + " 的邮件；"
                + "若验证码被 dev 信箱截留（路由接反）也会呈现这个形态。最后一批：" + lastBody);
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/v1/guest/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", PASSWORD))))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/v1/guest/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString())
                .get("token").asString();
    }
}
