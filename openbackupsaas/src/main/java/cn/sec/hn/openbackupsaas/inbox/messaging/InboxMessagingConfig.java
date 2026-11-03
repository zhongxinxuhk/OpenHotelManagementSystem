package cn.sec.hn.openbackupsaas.inbox.messaging;

import cn.sec.hn.openbackupsaas.shared.runtime.WorkerRoleCondition;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 消费侧队列与绑定（S2-02）。
 *
 * <p>队列由<b>消费方</b>声明，发布方不假设任何人已经绑好——这正是 S2-01 里
 * "交换机存在但没有队列时消息进了黑洞"能被 mandatory 发现的原因。
 *
 * <p>队列是 durable 的：消息持久化而队列不持久，Broker 重启后消息一样没了。
 * 死信队列与重试分级属 S2-04，这里先把"正常消费"这条路打通。
 *
 * <p>绑定键默认是<b>显式的事件类型列表</b>，不是 {@code Reservation*}。
 * 这不是啰嗦：topic 交换机的 {@code *} 必须独占一个词（{@code Reservation.*} 才匹配两段路由键），
 * {@code Reservation*} 会被当成一个字面词，绑定"声明成功"却一个事件都匹配不到——
 * 而这种失败在监控上表现为"绑定存在、消息全部不可路由"，很难一眼看出。
 * 当前路由键就是事件类型（单单词，无点），所以只能逐个列出。
 *
 * <p>每新增一个消费者，要么加路由键，要么新建独立队列——用通配符把所有事件塞进一个队列，
 * 会让"某类事件消费失败"拖住所有事件。
 */
@Configuration
@Conditional(WorkerRoleCondition.class)
@ConditionalOnProperty(name = "pms.inbox.enabled", havingValue = "true", matchIfMissing = true)
public class InboxMessagingConfig {

    @Bean
    Queue inboxQueue(@Value("${pms.inbox.queue:pms.inbox}") String queueName) {
        return QueueBuilder.durable(queueName).build();
    }

    @Bean
    TopicExchange inboxEventsExchange(@Value("${pms.outbox.exchange:pms.events}") String exchangeName) {
        // 与发布方声明的一致：durable topic。重复声明是幂等的。
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    Declarables inboxBindings(Queue inboxQueue,
                              TopicExchange inboxEventsExchange,
                              @Value("${pms.inbox.routing-keys:ReservationCreated,ReservationConfirmed,ReservationCancelled,ReservationNoShow,ReservationExpired,RoomOccupancyHeld,RoomOccupancyReleased}") String routingKeys) {
        List<Binding> bindings = new ArrayList<>();
        for (String key : routingKeys.split(",")) {
            String trimmed = key.trim();
            if (!trimmed.isEmpty()) {
                bindings.add(BindingBuilder.bind(inboxQueue).to(inboxEventsExchange).with(trimmed));
            }
        }
        if (bindings.isEmpty()) {
            throw new IllegalStateException("pms.inbox.routing-keys 不能为空：没有绑定键就不会收到任何事件");
        }
        return new Declarables(bindings);
    }
}
