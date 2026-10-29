package cn.sec.hn.openbackupsaas.journal.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 库存效果流水（只追加，S1-06）。
 *
 * <p>粒度是"房间 + 住宿日 + 效果种类"：一次住三晚写三行，不是一行带数量。
 * 这样重复入账会被唯一约束直接拒绝，而不是靠算术核对才发现。
 *
 * <p>释放写**对冲行**（delta = +1），不删原行：历史事实不能因为取消而消失。
 */
public record InventoryLedger(
        UUID id,
        UUID tenantId,
        UUID hotelId,
        UUID roomId,
        LocalDate stayDate,
        EffectKind effectKind,
        UUID operationId,
        Integer delta,
        String reasonCode,
        LocalDate effectiveFromDate,
        OffsetDateTime createdAt
) {

    /** 占用一晚：delta = -1。 */
    public static InventoryLedger occupy(UUID operationId, UUID tenantId, UUID hotelId, UUID roomId,
                                         LocalDate stayDate, EffectKind kind, String reasonCode,
                                         LocalDate effectiveFrom, OffsetDateTime now) {
        return new InventoryLedger(UUID.randomUUID(), tenantId, hotelId, roomId, stayDate, kind, operationId,
                -1, reasonCode, effectiveFrom, now);
    }

    /** 释放一晚：delta = +1（对冲，不删历史行）。 */
    public static InventoryLedger release(UUID operationId, UUID tenantId, UUID hotelId, UUID roomId,
                                          LocalDate stayDate, EffectKind kind, String reasonCode,
                                          LocalDate effectiveFrom, OffsetDateTime now) {
        return new InventoryLedger(UUID.randomUUID(), tenantId, hotelId, roomId, stayDate, kind, operationId,
                1, reasonCode, effectiveFrom, now);
    }
}
