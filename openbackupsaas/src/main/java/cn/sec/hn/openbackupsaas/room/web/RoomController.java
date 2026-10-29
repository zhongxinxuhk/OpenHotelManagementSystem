package cn.sec.hn.openbackupsaas.room.web;

import cn.sec.hn.openbackupsaas.room.domain.PhysicalRoom;
import cn.sec.hn.openbackupsaas.room.domain.RoomStatus;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.shared.security.HotelAccess;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 实体房间接口（S1-04）。
 *
 * <p>{@code housekeepingStatus} 只出现在响应里，不出现在任何请求体：
 * 清洁流转属 S3-06，且必须与销售事实解耦。要改清洁状态就明确走将来的房务接口，
 * 不允许顺手在房间更新里带上。
 */
@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/rooms")
public class RoomController {

    private final RoomService roomService;
    private final HotelAccess hotelAccess;

    public RoomController(RoomService roomService, HotelAccess hotelAccess) {
        this.roomService = roomService;
        this.hotelAccess = hotelAccess;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('room:write')")
    public RoomView create(@PathVariable UUID hotelId, @Valid @RequestBody CreateRoomRequest request) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        PhysicalRoom room = roomService.createRoom(tenantId, hotelId, request.roomTypeId(), request.code(),
                request.floor(), request.notes());
        return RoomView.of(room);
    }

    /** 列表条件都是可选的，但门店必选——不存在"跨店全量列表"。 */
    @GetMapping
    @PreAuthorize("hasAuthority('room:read')")
    public List<RoomView> list(@PathVariable UUID hotelId,
                               @RequestParam(required = false) UUID roomTypeId,
                               @RequestParam(required = false) RoomStatus status,
                               @RequestParam(required = false) Integer floor) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        return roomService.listRooms(tenantId, hotelId, roomTypeId, status, floor).stream()
                .map(RoomView::of)
                .toList();
    }

    @GetMapping("/{roomId}")
    @PreAuthorize("hasAuthority('room:read')")
    public RoomView get(@PathVariable UUID hotelId, @PathVariable UUID roomId) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        return RoomView.of(roomService.requireRoom(tenantId, hotelId, roomId));
    }

    @PatchMapping("/{roomId}")
    @PreAuthorize("hasAuthority('room:write')")
    public RoomView update(@PathVariable UUID hotelId,
                           @PathVariable UUID roomId,
                           @Valid @RequestBody UpdateRoomRequest request) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        PhysicalRoom room = roomService.updateRoomAttributes(tenantId, hotelId, roomId,
                request.roomTypeId(), request.floor(), request.notes());
        return RoomView.of(room);
    }

    @PostMapping("/{roomId}/status")
    @PreAuthorize("hasAuthority('room:write')")
    public RoomView changeStatus(@PathVariable UUID hotelId,
                                 @PathVariable UUID roomId,
                                 @Valid @RequestBody RoomStatusRequest request) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        return RoomView.of(roomService.changeRoomStatus(tenantId, hotelId, roomId, request.status()));
    }
}
