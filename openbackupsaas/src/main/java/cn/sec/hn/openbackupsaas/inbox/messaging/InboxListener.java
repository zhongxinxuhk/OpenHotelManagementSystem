package cn.sec.hn.openbackupsaas.inbox.messaging;

import cn.sec.hn.openbackupsaas.inbox.domain.InboxMessage;
import cn.sec.hn.openbackupsaas.inbox.service.InboxConsumer;
import cn.sec.hn.openbackupsaas.inbox.service.InboxProcessor;
import cn.sec.hn.openbackupsaas.shared.security.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Inbox 消息入口（S2-02）。
 *
 * <p>这一层只做三件事：解析、分发给订阅了该事件类型的消费者、把失败抛回去。
 * 去重与版本判定在 {@link InboxProcessor}，业务在 {@link InboxConsumer}。
 *
 * <h2>三种失败的区别（决定消息是重投还是丢弃）</h2>
 * <ul>
 *   <li><b>解析失败</b>：不可重试。同一条消息再投多少次都解析不了，抛出去只会把队列堵死 →
 *       记 ERROR 后<b>丢弃</b>。丢弃前必须留痕，否则就是"静默丢消息"。死信队列属 S2-04。</li>
 *   <li><b>业务失败</b>：可重试。异常必须向外抛，让容器按策略重投；此时 Inbox 台账
 *       已随事务回滚，不会留下"处理过"的假标记。</li>
 *   <li><b>重复/旧事件/缺口</b>：都不是失败，正常返回（缺口已落库挂起，之后可重放）。</li>
 * </ul>
 *
 * <h2>授权上下文</h2>
 * 消费者<b>不读也不伪造</b>线程级身份：租户来自消息体，每次调用显式传入。
 * 进入消息前先清掉线程上可能残留的上下文——Rabbit 监听线程会被复用，
 * 万一将来有人在别的路径上绑定过身份，不清就会把上一个租户带进来（串租户）。
 */
@Component
@ConditionalOnProperty(name = "pms.inbox.enabled", havingValue = "true")
public class InboxListener {

    private static final Logger log = LoggerFactory.getLogger(InboxListener.class);

    private final List<InboxConsumer> consumers;
    private final InboxProcessor processor;

    public InboxListener(List<InboxConsumer> consumers, InboxProcessor processor) {
        this.consumers = consumers;
        this.processor = processor;
    }

    @RabbitListener(queues = "${pms.inbox.queue:pms.inbox}")
    public void onMessage(org.springframework.amqp.core.Message raw) {
        String body = new String(raw.getBody(), StandardCharsets.UTF_8);
        InboxMessage message;
        try {
            message = InboxMessageCodec.parse(body);
        } catch (RuntimeException ex) {
            // 不可重试：重投不会让它变得可解析。留痕后丢弃（死信与人工重放属 S2-04）。
            log.error("无法解析的事件消息已丢弃（不可重试）：{}；原因：{}", body, ex.getMessage());
            return;
        }

        TenantContextHolder.clear();
        try {
            boolean handled = false;
            for (InboxConsumer consumer : consumers) {
                if (!consumer.supports(message.eventType())) {
                    continue;
                }
                handled = true;
                InboxProcessor.InboxResult result = processor.process(consumer, message);
                log.debug("事件 {} 由 {} 处理：结论 {} 重复={}",
                        message.eventId(), consumer.name(), result.outcome(), result.duplicate());
            }
            if (!handled) {
                // 没有任何消费者订阅该类型：这不是错误，但必须可见，否则"事件发了没人接"会长期无感。
                log.info("事件 {}({}) 没有订阅者，已跳过", message.eventId(), message.eventType());
            }
        } finally {
            // 谁绑定谁清理；这里虽然没绑定，但复用的监听线程上也绝不允许留下上下文。
            TenantContextHolder.clear();
        }
    }
}
