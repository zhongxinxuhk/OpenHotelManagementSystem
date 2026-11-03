package cn.sec.hn.openbackupsaas.reservation.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 订单（O）：一次预订的聚合根。
 *
 * <p>本轮只落结构与约束，**不提供**创建/确认/取消命令（S1-08/S1-09）——
 * 那些命令必须带上幂等记录、库存流水、审计与 Outbox，缺一样都不能自称"订单已创建"。
 *
 * <p>缺少 {@code operation_id} 是刻意的：幂等记录在 S1-07 才落表，
 * 与其先建一个空约束再改，不如等依赖到位一起加（见 V4 注释）。
 */
public record Reservation(
        UUID id,
        UUID tenantId,
        UUID hotelId,
        String reservationNo,
        ReservationSourceType sourceType,
        ReservationStatus status,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static Reservation create(UUID id, UUID tenantId, UUID hotelId, String reservationNo,
                                     ReservationSourceType sourceType, OffsetDateTime now) {
        return new Reservation(id, tenantId, hotelId, reservationNo, sourceType,
                ReservationStatus.PENDING, 0L, now, now);
    }
}
