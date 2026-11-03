package cn.sec.hn.openbackupsaas.reservation.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 订单行（L）：一间房需求，不设可变数量。
 *
 * <p>{@code plannedRange} 是购买区间，与实际分配的占用区间**不要求永久相等**：
 * 换房后 L 仍代表完整购买，而当前占用只表达切换后的区间（S0-04 §3.2）。
 * 因此不要写"L.planned_range == A.stay_range"这种断言式的校验。
 */
public record ReservationLine(
        UUID id,
        UUID tenantId,
        UUID hotelId,
        UUID reservationId,
        Integer lineNo,
        UUID requestedRoomTypeId,
        StayRange plannedRange,
        LineStatus status,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static ReservationLine create(UUID id, UUID tenantId, UUID hotelId, UUID reservationId, int lineNo,
                                         UUID requestedRoomTypeId, StayRange plannedRange, OffsetDateTime now) {
        return new ReservationLine(id, tenantId, hotelId, reservationId, lineNo, requestedRoomTypeId,
                plannedRange, LineStatus.PENDING, 0L, now, now);
    }
}
