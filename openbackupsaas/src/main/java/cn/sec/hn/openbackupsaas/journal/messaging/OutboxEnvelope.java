package cn.sec.hn.openbackupsaas.journal.messaging;

import cn.sec.hn.openbackupsaas.journal.domain.PendingOutboxEvent;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 投递出去的事件信封（S2-01）。
 *
 * <p>这是<b>对外契约</b>：消费方（S2-02）看到的字段从此固定，{@code schemaVersion}
 * 用于在不破坏老消费者的前提下扩展载荷。因此这里刻意不出现任何消息中间件类型
 * （不出现 {@code Message}、{@code JsonNode}），换 Broker 不该改消费契约。
 *
 * <p>{@code payload} 保持为原始 JSON 文本：它是在业务事务里写下的既成事实，
 * 发布器只负责搬运，<b>不做解析再序列化</b>——那会把精度丢失、字段顺序变化、
 * 未知字段被丢弃等问题引入到"已经定案的事实"上。
 *
 * <p>{@code tenantId} 必须随事件走：消费者靠它建立租户上下文，
 * 不允许消费者信任消息体之外的东西来猜租户。
 */
public record OutboxEnvelope(
        UUID eventId,
        UUID tenantId,
        UUID hotelId,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        int eventOrdinal,
        String eventType,
        int schemaVersion,
        String payload,
        OffsetDateTime occurredAt
) {

    public static OutboxEnvelope from(PendingOutboxEvent event) {
        return new OutboxEnvelope(
                event.eventId(),
                event.tenantId(),
                event.hotelId(),
                event.aggregateType(),
                event.aggregateId(),
                event.aggregateVersion() == null ? 0L : event.aggregateVersion(),
                event.eventOrdinal() == null ? 1 : event.eventOrdinal(),
                event.eventType(),
                event.schemaVersion() == null ? 1 : event.schemaVersion(),
                event.payload(),
                event.occurredAt());
    }
}
