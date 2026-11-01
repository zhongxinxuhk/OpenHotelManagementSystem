package cn.sec.hn.openbackupsaas.guest.ownership;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * 信箱方案下，码只在**服务进程内**流转；生产环境这个 Bean 不存在，
 * 发起挑战会得到明确的"发送通道未接通"（503），没有任何静默降级。
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

    /** dev 发送器：把验证码投进信箱。与信箱同生共死（同一个条件属性）。 */
    @Component
    @ConditionalOnProperty(name = "pms.guest.ownership.dev-mailbox", havingValue = "true")
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

    /**
     * 生产环境的"无实现"占位：任何发起挑战的请求都会得到 503。
     *
     * <p>为什么不是"不注册任何 Bean 然后用 ObjectProvider 判空"：
     * 判空逻辑散在服务层，有一天有人把空判断删了，挑战会静默"发送成功"——
     * 用户收不到码，重试若干次后过期。显式的抛错 Bean 让"未接通"是**默认且唯一**的行为。
     */
    @Component
    @ConditionalOnProperty(name = "pms.guest.ownership.dev-mailbox", havingValue = "false", matchIfMissing = true)
    public static class NoopOwnershipChallengeSender implements OwnershipChallengeSender {

        @Override
        public void send(ChallengeDelivery delivery) {
            throw new UnsupportedOperationException(
                    "验证码发送通道未接通（短信/邮件服务商未配置），无法完成归属验证");
        }
    }
}
