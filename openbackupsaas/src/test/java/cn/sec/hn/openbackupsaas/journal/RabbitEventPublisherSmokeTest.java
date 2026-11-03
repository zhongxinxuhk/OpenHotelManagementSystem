package cn.sec.hn.openbackupsaas.journal;

import cn.sec.hn.openbackupsaas.journal.messaging.EventPublishException;
import cn.sec.hn.openbackupsaas.journal.messaging.OutboxEnvelope;
import cn.sec.hn.openbackupsaas.journal.messaging.RabbitEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 RabbitMQ 冒烟（S2-01）。
 *
 * <p>为什么必须有这一层：{@link cn.sec.hn.openbackupsaas.journal.service.OutboxPublisherService}
 * 的单元级用例用可编程桩验证了"什么时候该重试、什么时候该标记"，但桩无法证明
 * <b>RabbitEventPublisher 真的等到了确认</b>、消息真的是持久化的、以及"发到没有绑定的路由键"
 * 真的会被识别为失败。这三条只能在真实 Broker 上验。
 *
 * <p>默认跳过（{@code PMS_IT_RABBIT=true} 才跑）：CI 的常规门禁不依赖消息中间件，
 * 否则"Broker 起不来"会让与消息无关的变更也红。启动方式见
 * {@code docs/verification/S2-01}。
 *
 * <p>连接参数取环境变量 {@code PMS_RABBIT_HOST}/{@code PMS_RABBIT_PORT}，
 * 默认 {@code 127.0.0.1:55672}（本地容器端口，避开默认 5672）。
 */
@EnabledIfEnvironmentVariable(named = "PMS_IT_RABBIT", matches = "true")
class RabbitEventPublisherSmokeTest {

    private static final String EXCHANGE = "pms.events.smoke";
    private static final String ROUTING_KEY = "ReservationConfirmed";

    private CachingConnectionFactory connectionFactory;
    private RabbitTemplate template;
    private RabbitEventPublisher publisher;
    private String queueName;

    @BeforeEach
    void setUp() {
        String host = System.getenv().getOrDefault("PMS_RABBIT_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("PMS_RABBIT_PORT", "55672"));
        connectionFactory = new CachingConnectionFactory(host, port);
        connectionFactory.setUsername(System.getenv().getOrDefault("PMS_RABBIT_USER", "guest"));
        connectionFactory.setPassword(System.getenv().getOrDefault("PMS_RABBIT_PASSWORD", "guest"));
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);

        template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        publisher = new RabbitEventPublisher(template, EXCHANGE, Duration.ofSeconds(10));

        queueName = "pms.smoke." + UUID.randomUUID();
        template.execute(channel -> {
            channel.exchangeDeclare(EXCHANGE, "topic", true);
            channel.queueDeclare(queueName, false, true, true, null);
            channel.queueBind(queueName, EXCHANGE, ROUTING_KEY);
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        try {
            if (queueName != null) {
                template.execute(channel -> {
                    channel.queueDelete(queueName);
                    return null;
                });
            }
        } finally {
            connectionFactory.destroy();
        }
    }

    @Test
    void publishesPersistentMessageAndReturnsOnlyAfterConfirmation() throws Exception {
        OutboxEnvelope envelope = envelope(ROUTING_KEY);

        publisher.publish(envelope);

        Message received = template.receive(queueName, 5000);
        assertNotNull(received, "消息应已到达队列");
        // AMQP 4 把"发送意图"与"线上实际值"拆成两个属性：收消息要看 receivedDeliveryMode，
        // getDeliveryMode() 在收到的消息上是 null（首跑就在这里误判过）。
        assertEquals(MessageDeliveryMode.PERSISTENT, received.getMessageProperties().getReceivedDeliveryMode(),
                "非持久消息在 Broker 重启后会消失，与'事件不能丢'冲突");
        assertEquals(envelope.eventId().toString(), received.getMessageProperties().getMessageId(),
                "messageId 必须是 eventId：消费侧靠它做去重（S2-02）");

        String body = new String(received.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains(envelope.eventId().toString()), "载荷应含 eventId：" + body);
        assertTrue(body.contains(envelope.tenantId().toString()), "载荷应含 tenantId：" + body);
        // 关键：业务载荷是嵌入的 JSON 对象，不是被转义的字符串
        assertTrue(body.contains("\"payload\":{\"reservationNo\":\"R-1\"}"),
                "载荷应原样嵌入而不是被转义成字符串：" + body);

        assertEquals(envelope.tenantId().toString(),
                received.getMessageProperties().getHeader("tenantId"), "租户也必须随消息头走");
    }

    @Test
    void unroutableMessageIsTreatedAsFailure() {
        // 交换机存在但没有队列绑定这个路由键：Broker 会 ack 然后丢弃，
        // 只看 ack 会把"进了黑洞"记成已发布。mandatory + returns 才能发现。
        OutboxEnvelope envelope = envelope("NoSuchEventType");

        assertThrows(EventPublishException.class, () -> publisher.publish(envelope),
                "不可路由必须抛异常，否则事件会被标记已发布却永远没人收到");
    }

    @Test
    void brokerDownIsReportedAsFailure() {
        connectionFactory.destroy();
        assertThrows(EventPublishException.class, () -> publisher.publish(envelope(ROUTING_KEY)),
                "连接不可用必须抛出而不是静默返回");
    }

    private static OutboxEnvelope envelope(String eventType) {
        return new OutboxEnvelope(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Reservation", UUID.randomUUID(), 3L, 1,
                eventType, 1,
                "{\"reservationNo\":\"R-1\"}",
                OffsetDateTime.now());
    }
}
