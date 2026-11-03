package cn.sec.hn.openbackupsaas.reservation.web;

import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

/**
 * 创建订单请求（S1-08）。
 *
 * <p>字段刻意精简到"足以原子建单"的程度：幂等键 + 来源 + 每行（房型 + 区间）。
 * 住客、价格、渠道单号等留待 S3-04 / L 域；订单状态不接受客户端设置（由命令推进）。
 *
 * <p>{@code idempotencyKey} 由调用方保证在"同一门店 + 同一客户端命名空间"下唯一；
 * 重复提交同一键会拿到原订单而不是新建。
 */
public record CreateReservationRequest(
        @NotNull String idempotencyKey,
        @NotNull ReservationSourceType sourceType,
        @NotEmpty @Valid List<LineRequest> lines
) {

    public record LineRequest(
            @NotNull UUID roomTypeId,
            @NotNull LocalDate checkIn,
            @NotNull LocalDate checkOut
    ) {
        public StayRange toRange() {
            return StayRange.of(checkIn, checkOut);
        }
    }
}
