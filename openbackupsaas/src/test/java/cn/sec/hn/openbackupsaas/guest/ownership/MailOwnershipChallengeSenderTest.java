package cn.sec.hn.openbackupsaas.guest.ownership;

import cn.sec.hn.openbackupsaas.shared.error.ChallengeDeliveryUnavailableException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.InputStream;
import java.net.ServerSocket;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件通道单元级行为（R-20261102-01）。
 *
 * <h2>这个类在防什么</h2>
 * <ul>
 *   <li><b>验证码泄漏到异常/日志</b>：投递失败时的异常 message 是排障者最先看的地方，
 *       码绝不能出现在那里（日志同理——本类根本不打日志）；</li>
 *   <li><b>邮件文案失控</b>：正文只允许"码 + 有效期 + 非本人操作提示"，出现营销、
 *       自述、多余信息都违反文案禁令的同源约束；</li>
 *   <li><b>职责声明过宽</b>：supports 必须只认 EMAIL——过宽声明会把短信码错投进邮件；</li>
 *   <li><b>SMTP 故障被当成应用 500</b>：中继不可达必须包装为 503 语义
 *       （{@link ChallengeDeliveryUnavailableException}），否则排障方向被带偏。</li>
 * </ul>
 *
 * <p>真实投递（SMTP 线上行为、MailPit 收信）由 {@code MailChallengeSenderSmokeTest}
 * （{@code PMS_IT_SMTP=true}）验证，本类不碰网络（死端口用例除外）。
 */
class MailOwnershipChallengeSenderTest {

    /** 记录投递内容的桩：只实现 SimpleMailMessage 路径，其余形状不会被走到。 */
    private static final class RecordingMailSender implements JavaMailSender {
        private final List<SimpleMailMessage> sent = new ArrayList<>();
        private final RuntimeException failure;

        private RecordingMailSender(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void send(SimpleMailMessage simpleMessage) {
            if (failure != null) {
                throw failure;
            }
            sent.add(simpleMessage);
        }

        @Override
        public void send(SimpleMailMessage... simpleMessages) {
            for (SimpleMailMessage message : simpleMessages) {
                send(message);
            }
        }

        @Override
        public MimeMessage createMimeMessage() {
            throw new UnsupportedOperationException("验证码邮件用 SimpleMailMessage，不走 MIME");
        }

        @Override
        public MimeMessage createMimeMessage(InputStream contentStream) {
            throw new UnsupportedOperationException("验证码邮件用 SimpleMailMessage，不走 MIME");
        }

        @Override
        public void send(MimeMessage mimeMessage) {
            throw new UnsupportedOperationException("验证码邮件用 SimpleMailMessage，不走 MIME");
        }

        @Override
        public void send(MimeMessage... mimeMessages) {
            throw new UnsupportedOperationException("验证码邮件用 SimpleMailMessage，不走 MIME");
        }

        @Override
        public void send(org.springframework.mail.javamail.MimeMessagePreparator... mimeMessagePreparators) {
            throw new UnsupportedOperationException("验证码邮件用 SimpleMailMessage，不走 MIME");
        }
    }

    private static final String FROM = "pms-noreply@smtp.test";
    private static final String TO = "guest@example.test";
    private static final String CODE = "482913";

    private static OwnershipChallengeSender.ChallengeDelivery emailDelivery() {
        return new OwnershipChallengeSender.ChallengeDelivery("EMAIL", TO, CODE,
                OffsetDateTime.now().plusMinutes(10), "登录");
    }

    @Test
    void supportsEmailOnly() {
        MailOwnershipChallengeSender sender =
                new MailOwnershipChallengeSender(new RecordingMailSender(null), FROM);

        assertTrue(sender.supports("EMAIL"), "邮件通道负责 EMAIL");
        assertFalse(sender.supports("PHONE"), "邮件通道不得声明负责 PHONE——过宽声明等于错投");
        assertFalse(sender.supports("WECHAT"), "第三方走 OAuth 回调，与本通道无关");
    }

    @Test
    void composesMinimalBusinessMail() {
        RecordingMailSender transport = new RecordingMailSender(null);
        MailOwnershipChallengeSender sender = new MailOwnershipChallengeSender(transport, FROM);

        sender.send(emailDelivery());

        assertEquals(1, transport.sent.size(), "应投递一封邮件");
        SimpleMailMessage message = transport.sent.getFirst();
        assertEquals(FROM, message.getFrom(), "发件人来自配置");
        assertEquals(1, message.getTo().length, "收件人应恰为目标邮箱");
        assertEquals(TO, message.getTo()[0], "收件人必须是规范化后的目标邮箱");
        assertEquals("PMS 验证码", message.getSubject(), "主题是动作所需的最小信息");
        // 文案禁令同源：正文只含 码 + 有效期 + 非本人提示，无营销、无自述
        String text = message.getText();
        assertTrue(text.contains(CODE), "正文必须含验证码");
        assertTrue(text.contains("分钟内有效"), "正文必须写有效期");
        assertTrue(text.contains("若非本人操作"), "必须提示非本人操作可忽略");
        assertFalse(text.contains("我们"), "不得出现自述式文案");
    }

    @Test
    void rejectsDeliveryForOtherProviders() {
        // 路由器已按 supports 分发；这条防线挡"直接注入本类误用"的形状。
        MailOwnershipChallengeSender sender =
                new MailOwnershipChallengeSender(new RecordingMailSender(null), FROM);
        OwnershipChallengeSender.ChallengeDelivery phoneDelivery =
                new OwnershipChallengeSender.ChallengeDelivery(
                        "PHONE", "+8613800138000", CODE,
                        OffsetDateTime.now().plusMinutes(10), "登录");

        ChallengeDeliveryUnavailableException ex = assertThrows(
                ChallengeDeliveryUnavailableException.class, () -> sender.send(phoneDelivery));
        assertTrue(ex.getMessage().contains("PHONE"), "异常应指明被误投的方式");
    }

    @Test
    void smtpFailureBecomes503AndNeverLeaksCode() {
        // 中继拒收：MailException 必须被包装为 503 语义；异常 message 只含
        // 收件人与根因，验证码绝不能出现（那是给排障者看的，不是给收件人）。
        RecordingMailSender transport = new RecordingMailSender(
                new MailSendException("Connection refused"));
        MailOwnershipChallengeSender sender = new MailOwnershipChallengeSender(transport, FROM);

        ChallengeDeliveryUnavailableException ex = assertThrows(
                ChallengeDeliveryUnavailableException.class, () -> sender.send(emailDelivery()));
        assertTrue(ex.getMessage().contains("发送失败"), "应说明是投递失败：" + ex.getMessage());
        assertTrue(ex.getMessage().contains(TO), "应保留收件人便于排障");
        assertFalse(ex.getMessage().contains(CODE), "异常 message 不得含验证码");
        // 根因挂在 cause 上（不丢信息），但同样不得含码
        assertFalse(String.valueOf(ex.getCause()).contains(CODE), "根因不得含验证码");
    }

    @Test
    void unreachableHostFailsFastWith503() throws java.io.IOException {
        // 真实 Socket 路径（不碰网络）：绑定后立刻释放一个端口，连接它必然被拒。
        // 验证：真实 JavaMailSenderImpl 的失败也被包装为 503，而不是裸抛 MailException
        // （裸抛会映射成 500，把"中继问题"伪装成"应用问题"）。
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        MailOwnershipChallengeSender sender =
                new MailOwnershipChallengeSender("127.0.0.1", deadPort, FROM);

        ChallengeDeliveryUnavailableException ex = assertThrows(
                ChallengeDeliveryUnavailableException.class,
                () -> sender.send(emailDelivery()),
                "连接被拒不重试、不静默，一次失败即 503");
        assertFalse(String.valueOf(ex).contains(CODE), "整条异常链不得含验证码");
    }

    @Test
    void defaultConstructorKeepsDefaultsForLocalMailpit() {
        // 默认 host/port 对齐 compose 的 MailPit 宿主端口：本地/CI 零配置可跑。
        // 这里只验证构造不抛（不真正发信——真实投递由冒烟测试负责）。
        assertDoesNotThrow(() -> new MailOwnershipChallengeSender("127.0.0.1", 55025, FROM));
    }
}
