package cn.sec.hn.openbackupsaas.reservation.web;

import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;

import java.util.List;
import java.util.UUID;

/**
 * 订单对外视图（S1-08）。
 *
 * <p>刻意<b>不回显</b>任何敏感字段（住客、支付、证件）；住客信息由 S3-04 / L 域在后续任务补全。
 * 幂等重放时只回 id（{@link #ofId}），避免为了"看起来完整"而去查一遍库。
 */
public record ReservationView(
        UUID id,
        String reservationNo,
        ReservationSourceType sourceType,
        ReservationStatus status,
        List<LineView> lines
) {

    public static ReservationView ofId(UUID id) {
        return new ReservationView(id, null, null, null, List.of());
    }

    /** 订单行视图：暴露"分配到了哪间房"，但不暴露房间号以外的房态细节。 */
    public record LineView(
            UUID lineId,
            UUID requestedRoomTypeId,
            UUID assignedRoomId,
            StayRange stayRange,
            UUID occupancyId
    ) {
    }
}
