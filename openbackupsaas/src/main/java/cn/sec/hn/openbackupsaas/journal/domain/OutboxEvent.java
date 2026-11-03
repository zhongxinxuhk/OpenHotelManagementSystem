package cn.sec.hn.openbackupsaas.journal.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Outbox 事件（S1-06）。
 *
 * <p>与业务写入同事务提交：业务成功则事件一定在，业务回滚则事件一定不在。
 * 至少一次投递与重复消费的去重由 S2-01/S2-02 负责，这里只保证"事实存在且不可改写"。
 *
 * <p>{@code payload} 一旦提交不再改写，只允许更新 {@code published_at} / 尝试次数 / 租约。
 */
public record OutboxEvent(
        UUID eventId,
        UUID tenantId,
        UUID hotelId,
        String aggregateType,
        UUID aggregateId,
        Long aggregateVersion,
        Integer eventOrdinal,
        String eventType,
        Integer schemaVersion,
        String payload,
        OffsetDateTime occurredAt,
        OffsetDateTime publishedAt,
        Integer attemptCount,
        OffsetDateTime nextAttemptAt,
        UUID leaseToken,
        OffsetDateTime leaseUntil
) {

    public static OutboxEvent pending(UUID tenantId, UUID hotelId, String aggregateType, UUID aggregateId,
                                      long aggregateVersion, int eventOrdinal, String eventType,
                                      Integer schemaVersion, String payload, OffsetDateTime now) {
        return new OutboxEvent(UUID.randomUUID(), tenantId, hotelId, aggregateType, aggregateId,
                aggregateVersion, eventOrdinal, eventType, schemaVersion, payload, now, null, 0, now,
                null, null);
    }
}
