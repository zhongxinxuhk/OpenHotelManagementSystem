package cn.sec.hn.openbackupsaas.reservation.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 订单列表行投影（S1-11）。
 *
 * <p>只带列表要展示的字段：整单跨度（最早入住 / 最晚离店）与行数由 SQL 聚合算出，
 * 不在应用层"拉全部订单行再 min/max"——那是 N+1 查询的经典写法。
 *
 * @param version 乐观版本：客户端拿它做确认/取消时的期望版本
 */
public record ReservationSummary(
        UUID id,
        String reservationNo,
        ReservationSourceType sourceType,
        ReservationStatus status,
        Long version,
        Integer lineCount,
        LocalDate checkIn,
        LocalDate checkOut,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
