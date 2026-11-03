package cn.sec.hn.openbackupsaas.reservation.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 房间占用事实（统一 A/B）。
 *
 * <p>核心不变量（由数据库 EXCLUDE 约束保证，不是靠本类检查）：
 * **同一房间的有效占用区间互斥**。相邻区间允许，因为区间是半开的。
 */
public record RoomOccupancy(
        UUID id,
        UUID tenantId,
        UUID hotelId,
        UUID roomId,
        OccupancyKind kind,
        UUID reservationId,
        UUID lineId,
        StayRange stayRange,
        OccupancyStatus status,
        OffsetDateTime expiresAt,
        String reasonCode,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /** 预订占用（HELD）：必须带订单/订单行与截止点。 */
    public static RoomOccupancy held(UUID id, UUID tenantId, UUID hotelId, UUID roomId,
                                     UUID reservationId, UUID lineId, StayRange stayRange,
                                     OffsetDateTime expiresAt, String reasonCode, OffsetDateTime now) {
        Objects.requireNonNull(reservationId, "预订占用必须带订单 id");
        Objects.requireNonNull(lineId, "预订占用必须带订单行 id");
        Objects.requireNonNull(expiresAt, "HELD 必须带截止点");
        return new RoomOccupancy(id, tenantId, hotelId, roomId, OccupancyKind.RESERVATION,
                reservationId, lineId, stayRange, OccupancyStatus.HELD, expiresAt, reasonCode, 0L, now, now);
    }

    /** 封房/维修：不带订单引用，也不带截止点（解除由显式命令完成，不靠时钟）。 */
    public static RoomOccupancy block(UUID id, UUID tenantId, UUID hotelId, UUID roomId,
                                      StayRange stayRange, String reasonCode, OffsetDateTime now) {
        return new RoomOccupancy(id, tenantId, hotelId, roomId, OccupancyKind.BLOCK,
                null, null, stayRange, OccupancyStatus.BLOCKED, null, reasonCode, 0L, now, now);
    }
}
