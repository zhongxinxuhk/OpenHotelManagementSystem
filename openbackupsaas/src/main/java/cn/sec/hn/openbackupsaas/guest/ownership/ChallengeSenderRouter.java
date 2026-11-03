package cn.sec.hn.openbackupsaas.guest.ownership;

import cn.sec.hn.openbackupsaas.shared.error.ChallengeDeliveryUnavailableException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 归属挑战发送器的分发（ADR-019 §2.10，R-20261102-01）。
 *
 * <p>方式（PHONE/EMAIL/…）到实现的映射在这里集中完成：按 Spring 注入顺序
 * （{@link Order} 值小者优先）找第一个 {@link OwnershipChallengeSender#supports(String)}
 * 的实现去发送。<b>找不到就是 503</b>。
 *
 * <h2>为什么需要一个路由器而不是像从前那样取"唯一实现"</h2>
 *
 * <p>EMAIL（SMTP）接通后，同一个进程里会同时存在「真实通道实现」与
 * 「dev 信箱实现」——{@code ObjectProvider#getIfAvailable()} 在多候选时直接抛
 * {@code NoUniqueBeanDefinitionException}，这不是假想而是必然发生。
 * 分发逻辑必须有唯一归属地，散到服务层会重现 R-20261101-33 里 Noop 存在的
 * 那个动机：判空/选择逻辑一多，总有人改错一处，让挑战静默"发送成功"。
 *
 * <h2>顺序契约</h2>
 *
 * <ul>
 *   <li>真实通道（如 Mail，{@code @Order(0)}）优先；</li>
 *   <li>dev 信箱（{@code @Order(100)}）兜底真实通道未覆盖的方式——
 *       本地联调常见组合是「EMAIL 走真实 SMTP 验证投递、PHONE 落信箱取码」，
 *       两者不冲突；</li>
 *   <li>两者都不支持（如 PHONE 在生产、未接短信）→ 503，与从前 Noop 的语义一致，
 *       但保证集中在这一处而不是散在实现里。</li>
 * </ul>
 */
@Component
public class ChallengeSenderRouter {

    /** Spring 对 List 注入按 @Order 升序排列；空列表是合法状态（= 全部方式未接通）。 */
    private final List<OwnershipChallengeSender> senders;

    public ChallengeSenderRouter(List<OwnershipChallengeSender> senders) {
        this.senders = List.copyOf(senders);
    }

    /**
     * 把一次投递交给负责该方式的实现。
     *
     * @throws ChallengeDeliveryUnavailableException 没有任何实现声明负责该方式（503）
     */
    public void send(OwnershipChallengeSender.ChallengeDelivery delivery) {
        for (OwnershipChallengeSender sender : senders) {
            if (sender.supports(delivery.provider())) {
                sender.send(delivery);
                return;
            }
        }
        throw new ChallengeDeliveryUnavailableException(
                "验证码发送通道未接通：" + delivery.provider() + " 暂无可用实现（短信服务商未定、"
                        + "邮件通道未开启时均为此形态），无法完成归属验证");
    }
}
