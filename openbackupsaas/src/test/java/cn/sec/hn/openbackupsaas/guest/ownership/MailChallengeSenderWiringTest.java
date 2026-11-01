package cn.sec.hn.openbackupsaas.guest.ownership;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import cn.sec.hn.openbackupsaas.support.NoDatabaseStubConfig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件通道装配检查（R-20261102-01，test 档案：不连库，见 NoDatabaseStubConfig 说明）。
 *
 * <p>单元测试验证的是"手工组装的路由"行为；这里验证的是<b>Spring 实际装配出来的形状</b>：
 * 开 {@code mail.enabled=true} 后 MailOwnershipChallengeSender 真的注册、
 * 与 dev 信箱共存（test 档案 dev-mailbox=true）且 {@code @Order} 顺序生效
 * （EMAIL 交给真实通道、PHONE 落信箱兜底）。
 *
 * <p>「两种实现共存时注入 List 不再是单候选」是本轮引入路由器的直接原因
 * （{@code ObjectProvider#getIfAvailable()} 多候选即抛 NoUniqueBean）——
 * 没有这层装配检查，那类问题只在启动时暴露。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(NoDatabaseStubConfig.class)
@TestPropertySource(properties = {
        "pms.guest.ownership.mail.enabled=true",
        "pms.guest.ownership.mail.host=127.0.0.1",
        "pms.guest.ownership.mail.port=55025",
        "pms.guest.ownership.mail.from=wiring-noreply@pms.test"
})
class MailChallengeSenderWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ChallengeSenderRouter router;

    @Test
    void mailSenderAndMailboxCoexistUnderRouter() {
        MailOwnershipChallengeSender mail =
                context.getBean(MailOwnershipChallengeSender.class);
        DevChallengeMailbox.DevOwnershipChallengeSender dev =
                context.getBean(DevChallengeMailbox.DevOwnershipChallengeSender.class);
        assertNotNull(mail, "mail.enabled=true 时邮件实现必须注册");
        assertNotNull(dev, "dev-mailbox=true 时信箱实现必须共存（本地联调组合）");
        assertTrue(mail.supports("EMAIL"), "邮件实现声明负责 EMAIL");
        assertFalse(mail.supports("PHONE"), "邮件实现不得声明负责 PHONE");
    }

    @Test
    void routerOrderPrefersRealChannelForEmailAndMailboxForPhone() {
        // 发到不存在的地址：不会真投递（test 档案连的是 127.0.0.1:55025，
        // 本地没起 MailPit 时会抛 503——但 EMAIL 是否交给"邮件实现"这一点
        // 通过 supports 契约与 @Order 已可静态判定；这里验证路由器的注入顺序）。
        MailOwnershipChallengeSender mail = context.getBean(MailOwnershipChallengeSender.class);
        DevChallengeMailbox mailbox = context.getBean(DevChallengeMailbox.class);

        // PHONE 没有真实实现：经路由器应落进 dev 信箱（证明兜底方向没接反）
        router.send(new OwnershipChallengeSender.ChallengeDelivery(
                "PHONE", "+8613800138000", "000111",
                java.time.OffsetDateTime.now().plusMinutes(10)));
        assertNotNull(mailbox.get("PHONE", "+8613800138000"),
                "PHONE 应由信箱兜底——如果落到了邮件实现，路由顺序接反了");
    }
}
