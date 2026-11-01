package cn.sec.hn.openbackupsaas.guest.ownership;

import cn.sec.hn.openbackupsaas.shared.error.ChallengeDeliveryUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * 验证码的邮件通道（ADR-019 §2.10 的 EMAIL 实现，R-20261102-01）。
 *
 * <p><b>仅当 {@code pms.guest.ownership.mail.enabled=true} 时存在</b>（默认 false）：
 * 「接通」必须是显式动作——没配置时 EMAIL 与 PHONE 一样回到 503，
 * 不存在"装了依赖就悄悄开始发信"的路径。host/port 由 {@code PMS_SMTP_*} 提供
 * （本地/CI 是 compose 里的 MailPit，预发/生产是内网 SMTP 中继）。
 *
 * <h2>这条通道的边界</h2>
 * <ul>
 *   <li><b>不启用 {@code spring.mail.*} 自动配置</b>：host/port/from 属于"归属验证"
 *       这一个用途，不是全局邮件能力。让它们出现在 spring 命名空间下，
 *       会诱使将来别的功能复用同一个发件人——那时验证码邮件的声誉管理
 *       （SPF/DKIM）就被无关邮件拖累。</li>
 *   <li><b>无认证、无 TLS</b>：内网中继与 MailPit 都不需要；需要 TLS 中继时
 *       再加 {@code mail.smtp.starttls.enable} 一类属性，不提前造配置面。</li>
 *   <li><b>发送失败包装为 503</b>（{@link ChallengeDeliveryUnavailableException}）：
 *       SMTP 拒收/连不上与"通道未接通"对调用方是同一种处置——稍后再试或换方式。
 *       绝不能把 {@link MailException} 原样抛出去变成 500：500 会引导排障者
 *       去查应用代码，而问题在中继。</li>
 *   <li><b>验证码不进日志、不进异常 message</b>：{@code delivery.code()} 只出现在
 *       邮件正文。异常里最多有 subject（收件地址）与根因，subject 在服务层日志里
 *       本来就是掩码形态。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "pms.guest.ownership.mail.enabled", havingValue = "true")
@Order(0)
public class MailOwnershipChallengeSender implements OwnershipChallengeSender {

    private static final String EMAIL = "EMAIL";

    private final JavaMailSender mailSender;
    private final String from;

    /** 测试用：注入可控的 {@link JavaMailSender} 桩验证邮件形态，不碰网络。 */
    MailOwnershipChallengeSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /**
     * 不依赖自动配置：host/port 是本域的配置（PMS_SMTP_HOST/PORT），
     * 由这里显式组装 {@link JavaMailSenderImpl}。
     *
     * <p>超时经 jakarta.mail 属性传（Spring 7 的 {@code JavaMailSenderImpl}
     * 已移除 {@code setConnectTimeout} 等便捷 setter）：验证码请求是同步链路，
     * 中继僵死时不能把请求线程拖到默认无限等待。10s 是"人愿意等一次发码"的上限。
     */
    // @Autowired：类里还有一个包私有的测试构造器，两个构造器并存时
    // Spring 无法自行取舍——显式指定这一个才是 Bean 的构造入口。
    @Autowired
    public MailOwnershipChallengeSender(
            @Value("${pms.guest.ownership.mail.host:127.0.0.1}") String host,
            @Value("${pms.guest.ownership.mail.port:55025}") int port,
            @Value("${pms.guest.ownership.mail.from:pms-noreply@localhost}") String from) {
        this(implFor(host, port), from);
    }

    private static JavaMailSenderImpl implFor(String host, int port) {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(host);
        impl.setPort(port);
        java.util.Properties mailProperties = new java.util.Properties();
        mailProperties.put("mail.smtp.connectiontimeout", "10000");
        mailProperties.put("mail.smtp.timeout", "10000");
        mailProperties.put("mail.smtp.writetimeout", "10000");
        impl.setJavaMailProperties(mailProperties);
        return impl;
    }

    @Override
    public boolean supports(String provider) {
        return EMAIL.equals(provider);
    }

    @Override
    public void send(ChallengeDelivery delivery) {
        if (!EMAIL.equals(delivery.provider())) {
            // 防御：路由器已按 supports 分发，这里挡"直接注入本类误用"的形状。
            throw new ChallengeDeliveryUnavailableException(
                    "邮件通道不负责 " + delivery.provider() + " 的验证码");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(delivery.subject());
        message.setSubject("PMS 验证码");
        // 文案遵守前端禁令同源原则：只给动作所需的最小信息，无营销、无自述。
        // 有效期从投递对象取，而不是再写死一份——挑战 TTL 改了这里不会说错话。
        message.setText("""
                验证码：%s

                %d 分钟内有效。若非本人操作，请忽略本邮件。""".formatted(
                delivery.code(),
                java.time.Duration.between(
                        java.time.OffsetDateTime.now(), delivery.expiresAt()).toMinutes()));
        try {
            mailSender.send(message);
        } catch (MailException ex) {
            // 根因信息保留（连接拒绝/超时等，不含邮件正文），验证码绝不在异常链里。
            throw new ChallengeDeliveryUnavailableException(
                    "验证码邮件发送失败（收件人 " + delivery.subject() + "）：" + ex.getMessage(), ex);
        }
    }
}
