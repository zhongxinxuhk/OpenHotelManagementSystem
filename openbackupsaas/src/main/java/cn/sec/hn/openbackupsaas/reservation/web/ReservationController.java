package cn.sec.hn.openbackupsaas.reservation.web;

import cn.sec.hn.openbackupsaas.reservation.service.ReservationCommandService;
import cn.sec.hn.openbackupsaas.shared.security.HotelAccess;
import cn.sec.hn.openbackupsaas.shared.security.TenantContextHolder;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 订单创建接口（S1-08）。
 *
 * <p>只开放"创建"：确认/取消/列表/详情属后续任务（S1-09 / S1-11）。
 * 调用主体命名空间（{@code X-Caller-Namespace}）进入幂等作用域，避免 A 客户端的键被 B 客户端重放顶替。
 *
 * <p>租户只来自可信身份（{@link TenantContextHolder}），门店先经 {@link HotelAccess} 校验
 * （不属于本租户 → 404，不在数据范围 → 403）。
 */
@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/reservations")
public class ReservationController {

    private final ReservationCommandService reservationCommandService;
    private final HotelAccess hotelAccess;

    public ReservationController(ReservationCommandService reservationCommandService, HotelAccess hotelAccess) {
        this.reservationCommandService = reservationCommandService;
        this.hotelAccess = hotelAccess;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('reservation:write')")
    public ReservationView create(@PathVariable UUID hotelId,
                                 @RequestHeader(value = "X-Caller-Namespace", defaultValue = "frontend")
                                         String callerNamespace,
                                 @Valid @RequestBody CreateReservationRequest request) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        ReservationCommandService.CreateCommand command = new ReservationCommandService.CreateCommand(
                hotelId, callerNamespace, request.idempotencyKey(), request.sourceType(),
                request.lines().stream()
                        .map(line -> new ReservationCommandService.CreateCommand.LineCommand(
                                line.roomTypeId(), line.toRange()))
                        .toList());
        return reservationCommandService.createReservation(tenantId, command);
    }

    @PostMapping("/{reservationId}/confirm")
    @PreAuthorize("hasAuthority('reservation:write')")
    public ReservationView confirm(@PathVariable UUID hotelId,
                                  @PathVariable UUID reservationId,
                                  @RequestHeader(value = "X-Caller-Namespace", defaultValue = "frontend")
                                          String callerNamespace,
                                  @Valid @RequestBody LifecycleRequest request) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        return reservationCommandService.confirmReservation(tenantId, reservationId,
                new ReservationCommandService.LifecycleCommand(hotelId, callerNamespace,
                        request.idempotencyKey(), request.expectedVersion()));
    }

    @PostMapping("/{reservationId}/cancel")
    @PreAuthorize("hasAuthority('reservation:write')")
    public ReservationView cancel(@PathVariable UUID hotelId,
                                 @PathVariable UUID reservationId,
                                 @RequestHeader(value = "X-Caller-Namespace", defaultValue = "frontend")
                                         String callerNamespace,
                                 @Valid @RequestBody LifecycleRequest request) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        return reservationCommandService.cancelReservation(tenantId, reservationId,
                new ReservationCommandService.LifecycleCommand(hotelId, callerNamespace,
                        request.idempotencyKey(), request.expectedVersion()));
    }

    @PostMapping("/{reservationId}/no-show")
    @PreAuthorize("hasAuthority('reservation:write')")
    public ReservationView markNoShow(@PathVariable UUID hotelId,
                                     @PathVariable UUID reservationId,
                                     @RequestHeader(value = "X-Caller-Namespace", defaultValue = "frontend")
                                             String callerNamespace,
                                     @Valid @RequestBody LifecycleRequest request) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        return reservationCommandService.markNoShow(tenantId, reservationId,
                new ReservationCommandService.LifecycleCommand(hotelId, callerNamespace,
                        request.idempotencyKey(), request.expectedVersion()));
    }
}
