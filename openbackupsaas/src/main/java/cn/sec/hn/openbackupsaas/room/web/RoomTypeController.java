package cn.sec.hn.openbackupsaas.room.web;

import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import io.swagger.v3.oas.annotations.Operation;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.shared.security.HotelAccess;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 房型接口（S1-04）。
 *
 * <p>每个方法都是"权限点 → 门店归属与范围 → 业务"三段的固定顺序：
 * {@code @PreAuthorize} 管能力，{@link HotelAccess} 管归属与范围，service 管业务规则。
 * 三段缺一就会出现"有权限但能跨店操作"或"能跨店但没权限"的缝。
 */
@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/room-types")
public class RoomTypeController {

    private final RoomService roomService;
    private final HotelAccess hotelAccess;

    public RoomTypeController(RoomService roomService, HotelAccess hotelAccess) {
        this.roomService = roomService;
        this.hotelAccess = hotelAccess;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createRoomType")
    @PreAuthorize("hasAuthority('room:write')")
    public RoomTypeView create(@PathVariable UUID hotelId, @Valid @RequestBody CreateRoomTypeRequest request) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        RoomType roomType = roomService.createRoomType(tenantId, hotelId, request.code(), request.name(),
                request.adultsOrTwo(), request.childrenOrZero(), request.bedType());
        return RoomTypeView.of(roomType);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('room:read')")
    @Operation(operationId = "listRoomTypes")
    public List<RoomTypeView> list(@PathVariable UUID hotelId) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        return roomService.listRoomTypes(tenantId, hotelId).stream().map(RoomTypeView::of).toList();
    }

    @GetMapping("/{roomTypeId}")
    @Operation(operationId = "getRoomType")
    @PreAuthorize("hasAuthority('room:read')")
    public RoomTypeView get(@PathVariable UUID hotelId, @PathVariable UUID roomTypeId) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        return RoomTypeView.of(roomService.requireRoomType(tenantId, hotelId, roomTypeId));
    }

    /** 启停用 POST + 子资源，而不是 PATCH 打整个对象：状态变更是独立业务动作，需要一个显式动作名。 */
    @PostMapping("/{roomTypeId}/status")
    @Operation(operationId = "changeRoomTypeStatus")
    @PreAuthorize("hasAuthority('room:write')")
    public RoomTypeView changeStatus(@PathVariable UUID hotelId,
                                     @PathVariable UUID roomTypeId,
                                     @Valid @RequestBody RoomTypeStatusRequest request) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        return RoomTypeView.of(roomService.changeRoomTypeStatus(tenantId, hotelId, roomTypeId, request.status()));
    }
}
