package cn.sec.hn.openbackupsaas.journal.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 被发布器领取到的一条待投递事件（S2-01）。
 *
 * <p>字段与 {@code outbox_event} 对应，但<b>只含投递所需的列</b>：这里不提供任何
 * {@code payload} 的写入口，也不提供 {@code publishedAt} 的写入口——
 * 载荷一旦提交就不可改写，发布状态只能由带租约校验的条件更新推进。
 *
 * <p>{@code attemptCount} 是<b>领取次数</b>而非成功次数：每次拿到租约即加一，
 * 所以它已经包含了本次领取。崩溃在发布前也会让它增长，这是租约式至少一次投递的正常代价。
 */
public record PendingOutboxEvent(
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
        Integer attemptCount,
        UUID leaseToken
) {
}
