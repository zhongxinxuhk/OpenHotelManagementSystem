package cn.sec.hn.openbackupsaas.guest.ownership;

import cn.sec.hn.openbackupsaas.shared.error.ChallengeDeliveryUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发送器路由（R-20261102-01）。
 *
 * <h2>这个类在防什么</h2>
 * <ul>
 *   <li><b>静默发送成功</b>：没有任何实现负责该方式时，最危险的形状是"什么都没发生
 *       但返回了成功"——用户收不到码反复重试直到挑战过期。必须抛 503；</li>
 *   <li><b>错通道投递</b>：EMAIL 的码发进了短信实现（或相反）——方式与实现的
 *       映射必须精确，声明过宽等于错投；</li>
 *   <li><b>真实通道被信箱遮蔽</b>：本地同时开 mail.enabled 与 dev-mailbox 时，
 *       EMAIL 必须走真实 SMTP（验证投递路径），只有真实通道没覆盖的方式才落信箱。</li>
 * </ul>
 */
class ChallengeSenderRouterTest {

    private record RecordedSend(String provider) {
    }

    /** 按声明只处理特定方式、记录调用的桩。 */
    private static final class RecordingSender implements OwnershipChallengeSender {
        private final String supportedProvider;
        private final List<RecordedSend> calls = new ArrayList<>();

        private RecordingSender(String supportedProvider) {
            this.supportedProvider = supportedProvider;
        }

        @Override
        public boolean supports(String provider) {
            return supportedProvider.equals(provider);
        }

        @Override
        public void send(OwnershipChallengeSender.ChallengeDelivery delivery) {
            calls.add(new RecordedSend(delivery.provider()));
        }
    }

    private static OwnershipChallengeSender.ChallengeDelivery delivery(String provider) {
        return new OwnershipChallengeSender.ChallengeDelivery(provider, "subject@example.test", "123456",
                java.time.OffsetDateTime.now().plusMinutes(10));
    }

    @Test
    void emptySenderListFailsExplicitly() {
        // 生产默认形态：短信未定、邮件未开 → 一个实现都没有。
        // 从前由 Noop 保证"显式失败"，现在由路由器集中保证（503 而非静默成功）。
        ChallengeSenderRouter router = new ChallengeSenderRouter(List.of());

        ChallengeDeliveryUnavailableException ex = assertThrows(
                ChallengeDeliveryUnavailableException.class,
                () -> router.send(delivery("PHONE")));
        assertTrue(ex.getMessage().contains("PHONE"), "异常应指明未接通的方式：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("验证码"), "异常应说明这是验证码投递问题");
    }

    @Test
    void unmatchedProviderFailsEvenWithOtherSenders() {
        // 有实现、但不负责该方式：同样 503。半接通状态（只有邮件实现）里
        // 发起 PHONE 挑战就是这条路径——不能因为"有个实现存在"就放过。
        RecordingSender mail = new RecordingSender("EMAIL");
        ChallengeSenderRouter router = new ChallengeSenderRouter(List.of(mail));

        assertThrows(ChallengeDeliveryUnavailableException.class,
                () -> router.send(delivery("PHONE")));
        assertTrue(mail.calls.isEmpty(), "邮件实现不应被调用来发短信码");
    }

    @Test
    void routesToTheImplementationThatSupportsTheProvider() {
        RecordingSender mail = new RecordingSender("EMAIL");
        RecordingSender sms = new RecordingSender("PHONE");
        ChallengeSenderRouter router = new ChallengeSenderRouter(List.of(mail, sms));

        router.send(delivery("EMAIL"));
        router.send(delivery("PHONE"));

        assertEquals(List.of(new RecordedSend("EMAIL")), mail.calls, "EMAIL 应交给邮件实现");
        assertEquals(List.of(new RecordedSend("PHONE")), sms.calls, "PHONE 应交给短信实现");
    }

    @Test
    void earlierImplementationWinsForSameProvider() {
        // @Order 契约：真实通道在前（Order 值小），dev 信箱兜底在后。
        // 同一方式有两个实现声明负责时，路由必须确定性：取注入列表里的第一个，
        // 而不是"看 Spring 心情"。这里用列表顺序模拟 Spring 的 @Order 排序结果。
        RecordingSender real = new RecordingSender("EMAIL");
        RecordingSender devMailbox = new RecordingSender("EMAIL");
        ChallengeSenderRouter router = new ChallengeSenderRouter(List.of(real, devMailbox));

        router.send(delivery("EMAIL"));

        assertEquals(1, real.calls.size(), "真实通道应优先处理 EMAIL");
        assertTrue(devMailbox.calls.isEmpty(), "真实通道已处理，信箱不应再收一份");
    }

    @Test
    void realChannelHandlesItsProviderAndMailboxFillsTheGap() {
        // 本地联调的典型组合：EMAIL 走真实 SMTP、PHONE 落 dev 信箱。
        // 信箱 supports 默认全开（什么都能收），排在真实通道之后。
        RecordingSender real = new RecordingSender("EMAIL");
        List<RecordedSend> mailboxCalls = new ArrayList<>();
        OwnershipChallengeSender devMailbox = new OwnershipChallengeSender() {
            @Override
            public void send(OwnershipChallengeSender.ChallengeDelivery delivery) {
                mailboxCalls.add(new RecordedSend(delivery.provider()));
            }
        };
        ChallengeSenderRouter router = new ChallengeSenderRouter(List.of(real, devMailbox));

        router.send(delivery("EMAIL"));
        router.send(delivery("PHONE"));

        assertEquals(List.of(new RecordedSend("EMAIL")), real.calls,
                "EMAIL 属于真实通道的职责范围");
        assertEquals(List.of(new RecordedSend("PHONE")), mailboxCalls,
                "真实通道不管 PHONE，应落信箱兜底");
    }
}
