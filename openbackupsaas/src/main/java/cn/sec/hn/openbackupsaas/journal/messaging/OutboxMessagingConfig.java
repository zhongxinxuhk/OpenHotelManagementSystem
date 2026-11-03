package cn.sec.hn.openbackupsaas.journal.messaging;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 发布通道装配（S2-01）。
 *
 * <p>目前只有 RabbitMQ 一个实现（ADR-011 §2「消息」：只选 RabbitMQ）。
 * <b>刻意不提供"只打日志的假发布器"</b>：那种实现会让 {@code published_at} 被写上而消息从未离开
 * 进程，等于把丢事件伪装成成功。没有 Broker 的环境请关掉轮询
 * （{@code pms.outbox.poller.enabled=false}），事件会老实留在库里。
 *
 * <h2>为什么在这里改 ConnectionFactory</h2>
 * 确认机制必须在连接工厂上开启（{@code publisherConfirmType} / {@code publisherReturns}），
 * 模板层开不了。这段代码显式改而不是只写配置文件，是因为<b>属性名写错不会报错</b>：
 * 未知属性被忽略，确认没开，发布器就会把"消息进了 socket"当成"Broker 已收下"。
 * 写在代码里，改错即编译失败；写在属性里，改错即静默失守。
 */
@Configuration
@ConditionalOnProperty(name = "pms.outbox.publisher", havingValue = "rabbit", matchIfMissing = true)
public class OutboxMessagingConfig {

    @Bean
    RabbitTemplate outboxRabbitTemplate(ConnectionFactory connectionFactory) {
        if (connectionFactory instanceof CachingConnectionFactory caching) {
            caching.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            caching.setPublisherReturns(true);
        }
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        // mandatory：交换机存在但没有队列绑定时，Broker 会 ack 后丢弃；
        // 不开 mandatory 就永远发现不了"消息进了黑洞"。
        template.setMandatory(true);
        return template;
    }

    @Bean
    EventPublisher outboxEventPublisher(RabbitTemplate outboxRabbitTemplate,
                                        @Value("${pms.outbox.exchange:pms.events}") String exchange,
                                        @Value("${pms.outbox.confirm-timeout:PT5S}") Duration confirmTimeout) {
        return new RabbitEventPublisher(outboxRabbitTemplate, exchange, confirmTimeout);
    }
}
