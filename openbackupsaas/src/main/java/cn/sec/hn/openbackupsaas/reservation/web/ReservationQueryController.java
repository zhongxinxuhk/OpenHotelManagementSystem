package cn.sec.hn.openbackupsaas.reservation.web;

import cn.sec.hn.openbackupsaas.reservation.domain.ReservationQuery;
import io.swagger.v3.oas.annotations.Operation;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSummary;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationQueryService;
import cn.sec.hn.openbackupsaas.shared.security.HotelAccess;
import cn.sec.hn.openbackupsaas.shared.web.PageView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 订单查询接口（S1-11）：列表与详情，只读。
 *
 * <p>与 {@link ReservationController}（命令侧）共用同一路径前缀是安全的：
 * 它们的 (HTTP 方法, 路径) 组合不重叠——命令侧是 POST，这里是 GET。
 * 分开成两个类是因为两侧的依赖、事务语义与演进速度完全不同：
 * 命令侧会继续长（入住、离店、换房），查询侧会继续长（聚合、导出），
 * 挤在一个类里迟早变成几百行的混合体。
 *
 * <p>权限：两个接口都要求 {@code reservation:read}。门店先经 {@link HotelAccess} 校验
 * （不属于本租户 → 404，不在数据范围 → 403），再带着校验过的租户 id 进入查询。
 * 因此不存在"传一个别的门店 id 就能查到别人订单"的路径。
 */
@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/reservations")
public class ReservationQueryController {

    private final ReservationQueryService queryService;
    private final HotelAccess hotelAccess;

    public ReservationQueryController(ReservationQueryService queryService, HotelAccess hotelAccess) {
        this.queryService = queryService;
        this.hotelAccess = hotelAccess;
    }

    /**
     * 订单列表。
     *
     * <p>参数语义：
     * <ul>
     *   <li>{@code status} 可重复（{@code ?status=PENDING&status=CONFIRMED}），不传表示不限；</li>
     *   <li>{@code stayFrom/stayTo} 必须成对出现，按<b>区间交集</b>筛选
     *       （查 5/1—5/3，5/2 入住 5/4 离店的单也应命中），不是"某天落在区间内"；</li>
     *   <li>{@code no} 是订单号<b>前缀</b>，不是模糊匹配——中间匹配用不上索引，等于全表扫；</li>
     *   <li>{@code page} 从 1 开始，{@code size} 上限 100。</li>
     * </ul>
     * 不接受客户端指定排序：排序固定为 {@code createdAt DESC, id DESC}（唯一、稳定）。
     */
    @GetMapping
    @PreAuthorize("hasAuthority('reservation:read')")
    @Operation(operationId = "listReservations")
    public PageView<ReservationSummary> list(
            @PathVariable UUID hotelId,
            @RequestParam(required = false) List<ReservationStatus> status,
            @RequestParam(required = false) ReservationSourceType sourceType,
            @RequestParam(required = false) String no,
            @RequestParam(required = false) String roomCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate stayFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate stayTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        ReservationQuery query = new ReservationQuery(tenantId, hotelId, status, sourceType,
                no, roomCode, stayFrom, stayTo, page, size);
        return queryService.search(query);
    }

    /**
     * 订单详情。
     *
     * <p>返回里带 {@code allowedActions}（由状态机守卫算出）与 {@code version}
     * （确认/取消的期望版本）。不含租户 id、占用 id 等实现事实——脱敏的正确做法是
     * 不下发不需要的数据，而不是下发后再遮起来。
     */
    @GetMapping("/{reservationId}")
    @Operation(operationId = "getReservationDetail")
    @PreAuthorize("hasAuthority('reservation:read')")
    public ReservationDetailView detail(@PathVariable UUID hotelId, @PathVariable UUID reservationId) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        return queryService.detail(tenantId, hotelId, reservationId);
    }
}
