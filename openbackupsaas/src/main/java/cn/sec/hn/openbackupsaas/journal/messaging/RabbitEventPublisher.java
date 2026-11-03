package cn.sec.hn.openbackupsaas.journal.messaging;

import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RabbitMQ 实现的事件发布（S2-01）。
 *
 * <h2>三条保证，缺一不可</h2>
 * <ol>
 *   <li><b>确认后才算成功</b>：开启 publisher confirm（correlated），并<b>等待</b>确认结果。
 *       只调用 {@code convertAndSend} 就返回，等于把"写进 socket"当成了"Broker 已收下"——
 *       连接恰好断开时消息会静默消失，而库里的 {@code published_at} 已经写了。</li>
 *   <li><b>不可路由也算失败</b>：{@code mandatory=true} + publisher returns。交换机存在但没有
 *       队列绑定时，Broker 会确认（ack）却把消息丢掉；只看 ack 会把"进了黑洞"记成成功。</li>
 *   <li><b>持久化投递</b>：{@code deliveryMode=PERSISTENT} 且交换机 durable。
 *       非持久消息在 Broker 重启时消失，与"事件不能丢"直接冲突。</li>
 * </ol>
 *
 * <h2>为什么交换机在这里声明</h2>
 * 声明是一次幂等操作，放在首次发布时做，而不是启动时做：启动时声明意味着
 * <b>Broker 停机会导致应用起不来</b>，而要求是"Broker 停机时事件留库并告警"——
 * API 进程不该因为消息系统不可用而拒绝服务。
 *
 * <h2>路由键</h2>
 * 用 {@code eventType}（如 {@code ReservationConfirmed}）作路由键、topic 交换机。
 * 消费侧的队列与绑定属于 S2-02，发布器不假设任何人已经绑好。
 */
public class RabbitEventPublisher implements EventPublisher {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final RabbitTemplate template;
    private final String exchange;
    private final Duration confirmTimeout;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicBoolean exchangeDeclared = new AtomicBoolean(false);

    public RabbitEventPublisher(RabbitTemplate template, String exchange, Duration confirmTimeout) {
        this.template = template;
        this.exchange = exchange;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void publish(OutboxEnvelope envelope) throws EventPublishException {
        ensureExchange();
        String body = toJson(envelope);
        CorrelationData correlation = new CorrelationData(envelope.eventId().toString());

        try {
            template.convertAndSend(exchange, envelope.eventType(), body, message -> {
                var properties = message.getMessageProperties();
                properties.setContentType(MediaType.APPLICATION_JSON_VALUE);
                properties.setContentEncoding("UTF-8");
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setMessageId(envelope.eventId().toString());
                properties.setHeader("tenantId", envelope.tenantId().toString());
                if (envelope.hotelId() != null) {
                    properties.setHeader("hotelId", envelope.hotelId().toString());
                }
                properties.setHeader("aggregateType", envelope.aggregateType());
                properties.setHeader("aggregateId", envelope.aggregateId().toString());
                properties.setHeader("aggregateVersion", envelope.aggregateVersion());
                properties.setHeader("eventOrdinal", envelope.eventOrdinal());
                properties.setHeader("eventType", envelope.eventType());
                properties.setHeader("schemaVersion", envelope.schemaVersion());
                return message;
            }, correlation);
        } catch (RuntimeException ex) {
            // 连接被拒、频道关闭、序列化失败等都落在这里：一律视为"未确认"
            throw new EventPublishException("发送失败：" + rootMessage(ex), ex);
        }

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("等待确认被中断", ex);
        } catch (Exception ex) {
            throw new EventPublishException("等待确认超时（" + confirmTimeout.toSeconds() + " 秒）", ex);
        }

        // Confirm 是 record：spring-amqp 4.x 起访问器为 ack() / reason()，
        // isAck() / getReason() 已标记 @Deprecated(forRemoval = true)。
        if (!confirm.ack()) {
            throw new EventPublishException("Broker 拒绝消息：" + confirm.reason());
        }

        ReturnedMessage returned = correlation.getReturned();
        if (returned != null) {
            throw new EventPublishException("消息不可路由（replyCode=" + returned.getReplyCode()
                    + "，routingKey=" + envelope.eventType() + "）：交换机 " + exchange + " 上没有匹配的队列");
        }
    }

    private void ensureExchange() throws EventPublishException {
        if (exchangeDeclared.get()) {
            return;
        }
        try {
            template.execute(channel -> {
                // AMQP 4 用 String 常量 ExchangeTypes.*；BuiltinExchangeType 已移除
                channel.exchangeDeclare(exchange, ExchangeTypes.TOPIC, true);
                return null;
            });
            exchangeDeclared.set(true);
        } catch (RuntimeException ex) {
            throw new EventPublishException("声明交换机 " + exchange + " 失败：" + rootMessage(ex), ex);
        }
    }

    private String toJson(OutboxEnvelope envelope) throws EventPublishException {
        ObjectNode root = json.createObjectNode();
        root.put("eventId", envelope.eventId().toString());
        root.put("eventType", envelope.eventType());
        root.put("schemaVersion", envelope.schemaVersion());
        root.put("occurredAt", ISO.format(envelope.occurredAt()));
        root.put("tenantId", envelope.tenantId().toString());
        if (envelope.hotelId() != null) {
            root.put("hotelId", envelope.hotelId().toString());
        }
        root.put("aggregateType", envelope.aggregateType());
        root.put("aggregateId", envelope.aggregateId().toString());
        root.put("aggregateVersion", envelope.aggregateVersion());
        root.put("eventOrdinal", envelope.eventOrdinal());
        try {
            // 载荷以原始 JSON 嵌入，不经过"解析成对象再序列化"的往返：
            // 后者会引入精度与字段顺序变化，而这是一条已成事实的事件。
            root.set("payload", json.readTree(envelope.payload()));
        } catch (RuntimeException ex) {
            throw new EventPublishException("事件 " + envelope.eventId() + " 的载荷不是合法 JSON", ex);
        }
        return json.writeValueAsString(root);
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        String message = ex.getMessage();
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
        }
        return message == null ? ex.getClass().getSimpleName() : message;
    }
}
