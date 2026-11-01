package cn.sec.hn.openbackupsaas.guest.ownership;

import cn.sec.hn.openbackupsaas.shared.error.ChallengeDeliveryUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 SMTP 投递冒烟（R-20261102-01）。
 *
 * <p>为什么必须有这一层：单元级用例验证了"邮件形态对、失败会抛"，但桩无法证明
 * <b>JavaMailSender 真的完成了 SMTP 会话</b>（握手、信封、正文编码）——
 * 协议层的行为（超时属性写错 key、STARTTLS 误协商、from 被拒）只在真实服务器上暴露。
 * 收信端是 compose 里的 MailPit（与 PostgreSQL、RabbitMQ 同一份编排，ADR-019 §2.10 落地）。
 *
 * <p>默认跳过（{@code PMS_IT_SMTP=true} 才跑），与 {@code RabbitEventPublisherSmokeTest}
 * 同一模式：CI 由 compose 提供服务并显式打开开关（与零跳过断言咬合）；
 * 本地先 {@code docker compose up -d --wait}（deploy/compose）。
 *
 * <p>连接参数取环境变量 {@code PMS_SMTP_HOST}/{@code PMS_SMTP_PORT}（默认
 * {@code 127.0.0.1:55025}，即 compose 的 mailpit 宿主端口）；MailPit 查询 API
 * 取 {@code PMS_MAILPIT_UI_PORT}（默认 8025）。
 *
 * <p>这是**发送器层**的冒烟：它证明"码离开了进程并出现在真实邮件正文里"。
 * 挑战落库、一次性消费、尝试上限等数据库行为由 {@code GuestBindingFlowTest}
 * （真实 PostgreSQL）钉死——两层各管一段，不重复。
 */
@EnabledIfEnvironmentVariable(named = "PMS_IT_SMTP", matches = "true")
class MailChallengeSenderSmokeTest {

    private static final String CODE = "739105";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void deliversCodeThroughRealSmtpSession() throws Exception {
        // 收件地址唯一化：MailPit 里可能残留此前运行的消息，靠地址过滤出"这一次"的邮件。
        String mailbox = "smoke-" + UUID.randomUUID() + "@pms.test";
        MailOwnershipChallengeSender sender = new MailOwnershipChallengeSender(
                smtpHost(), smtpPort(), "smoke-noreply@pms.test");

        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(10);
        sender.send(new OwnershipChallengeSender.ChallengeDelivery(
                "EMAIL", mailbox, CODE, expiresAt));

        JsonNode message = awaitMessageFor(mailbox, Duration.ofSeconds(10));
        String text = message.get("Text").asString();
        assertTrue(text.contains(CODE), "验证码必须出现在真实邮件正文中：" + text);
        assertEquals("PMS 验证码", message.get("Subject").asString(), "主题应为最小业务文案");
        assertTrue(text.contains("分钟内有效"), "正文应含有效期提示");
    }

    @Test
    void senderDoesNotPretendWhenRelayRejects() {
        // 真实 Socket 层的失败路径（连一个必然被拒的本地端口）：
        // 冒烟环境里顺手再钉一次"绝不静默成功"，防的是将来有人把包装逻辑删掉。
        MailOwnershipChallengeSender sender = new MailOwnershipChallengeSender(
                smtpHost(), 1, "smoke-noreply@pms.test");

        ChallengeDeliveryUnavailableException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ChallengeDeliveryUnavailableException.class,
                () -> sender.send(new OwnershipChallengeSender.ChallengeDelivery(
                        "EMAIL", "smoke-reject@pms.test", CODE,
                        OffsetDateTime.now().plusMinutes(10))));
        assertTrue(String.valueOf(ex).contains("发送失败"), "应显式报告投递失败");
    }

    // ==================== 工具 ====================

    private static String smtpHost() {
        return System.getenv().getOrDefault("PMS_SMTP_HOST", "127.0.0.1");
    }

    private static int smtpPort() {
        return Integer.parseInt(System.getenv().getOrDefault("PMS_SMTP_PORT", "55025"));
    }

    private static int mailpitUiPort() {
        return Integer.parseInt(System.getenv().getOrDefault("PMS_MAILPIT_UI_PORT", "8025"));
    }

    /** 轮询 MailPit 直到出现发给该地址的邮件；超时即失败（附 API 原始响应便于排障）。 */
    private JsonNode awaitMessageFor(String mailbox, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String lastBody = "";
        while (System.nanoTime() < deadline) {
            HttpRequest list = HttpRequest.newBuilder()
                    .uri(URI.create("http://%s:%d/api/v1/messages?limit=50"
                            .formatted(smtpHost(), mailpitUiPort())))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(list, HttpResponse.BodyHandlers.ofString());
            lastBody = response.body();
            for (JsonNode summary : json.readTree(lastBody).get("messages")) {
                for (JsonNode to : summary.get("To")) {
                    if (mailbox.equals(to.get("Address").asString())) {
                        // 列表接口不含正文，逐条取详情
                        HttpRequest detail = HttpRequest.newBuilder()
                                .uri(URI.create("http://%s:%d/api/v1/message/%s"
                                        .formatted(smtpHost(), mailpitUiPort(),
                                                summary.get("ID").asString())))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build();
                        HttpResponse<String> detailResponse =
                                http.send(detail, HttpResponse.BodyHandlers.ofString());
                        return json.readTree(detailResponse.body());
                    }
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError("MailPit 在 " + timeout + " 内未收到发给 " + mailbox
                + " 的邮件；最后一批消息：" + lastBody);
    }
}
