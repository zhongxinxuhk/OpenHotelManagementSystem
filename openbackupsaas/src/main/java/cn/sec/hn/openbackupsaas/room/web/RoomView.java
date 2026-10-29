package cn.sec.hn.openbackupsaas.room.web;

import cn.sec.hn.openbackupsaas.room.domain.PhysicalRoom;

import java.util.UUID;

/**
 * 房间对外投影。
 *
 * <p>同时回显 {@code status}（运营）与 {@code housekeepingStatus}（房务）是刻意的：
 * 客户端要能看到两类状态并存且互不覆盖。但 {@code housekeepingStatus} 本轮**只读**，
 * 写入口归 S3-06。
 */
public record RoomView(
        UUID id,
        UUID roomTypeId,
        String code,
        Integer floor,
        String status,
        String housekeepingStatus,
        String notes,
        Long version
) {

    public static RoomView of(PhysicalRoom room) {
        return new RoomView(room.id(), room.roomTypeId(), room.code(), room.floor(),
                room.status().name(), room.housekeepingStatus().name(), room.notes(), room.version());
    }
}
