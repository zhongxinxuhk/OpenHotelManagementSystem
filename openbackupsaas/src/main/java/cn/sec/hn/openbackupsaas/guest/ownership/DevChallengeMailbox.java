package cn.sec.hn.openbackupsaas.guest.ownership;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开发/测试用的"验证码信箱"（ADR-019 §2.10）。
 *
 * <p><b>仅当 {@code pms.guest.ownership.dev-mailbox=true} 时存在</b>
 * （见 {@link DevOwnershipChallengeSender}）。它把验证码放进内存，
 * 开发联调与测试从这里取码走完 confirm 流程——短信服务商接通前的替代品。
 *
 * <p><b>为什么码不能直接放进 HTTP 响应</b>：挑战响应发给的是"发起绑定的那个人"，
 * 而归属验证要确认的是"目标手机号的主人"。把码放进响应等于宣告
 * "任何登录用户都可以绑定任何手机号"——账户接管只需要一个会话。
 * 信箱方案下，码只在**服务进程内**流转。
 *
 * <p>「未接通」的 503 行为由 {@link ChallengeSenderRouter} 集中保证（R-20261102-01 起）：
 * 本类不再提供 Noop 占位实现——路由器对"没有任何实现支持该方式"的处理就是抛
 * {@code ChallengeDeliveryUnavailableException}，保证仍然集中且唯一。
 */
@Component
@ConditionalOnProperty(name = "pms.guest.ownership.dev-mailbox", havingValue = "true")
public class DevChallengeMailbox {

    private final Map<String, String> codesBySubject = new ConcurrentHashMap<>();

    /** 供 {@link DevOwnershipChallengeSender} 投递、测试断言读取。key = provider:subject。 */
    public void put(String provider, String subject, String code) {
        codesBySubject.put(key(provider, subject), code);
    }

    /** 取走验证码（读取后不清除：同一 challenge 的 confirm 可能重试？不——confirm 成败都终止。清除留给过期清理）。 */
    public String get(String provider, String subject) {
        return codesBySubject.get(key(provider, subject));
    }

    public void clear(String provider, String subject) {
        codesBySubject.remove(key(provider, subject));
    }

    public void clearAll() {
        codesBySubject.clear();
    }

    private static String key(String provider, String subject) {
        return provider + ":" + subject;
    }

    /**
     * dev 发送器：把验证码投进信箱。与信箱同生共死（同一个条件属性）。
     *
     * <p>{@code @Order(100)}：在路由顺序上排在真实通道（{@code @Order(0)}）之后，
     * 作为「真实通道没覆盖的方式」的兜底。典型组合：EMAIL 走 SMTP 真投递、
     * PHONE 落信箱——两个都开时互不抢活。supports 用默认实现（全部方式）：
     * 信箱本来就是"什么都能收"的开发替代品。
     */
    @Component
    @ConditionalOnProperty(name = "pms.guest.ownership.dev-mailbox", havingValue = "true")
    @Order(100)
    public static class DevOwnershipChallengeSender implements OwnershipChallengeSender {

        private final DevChallengeMailbox mailbox;

        public DevOwnershipChallengeSender(DevChallengeMailbox mailbox) {
            this.mailbox = mailbox;
        }

        @Override
        public void send(ChallengeDelivery delivery) {
            mailbox.put(delivery.provider(), delivery.subject(), delivery.code());
        }
    }
}
