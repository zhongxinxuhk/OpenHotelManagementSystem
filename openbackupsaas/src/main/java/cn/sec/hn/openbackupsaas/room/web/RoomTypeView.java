package cn.sec.hn.openbackupsaas.room.web;

import cn.sec.hn.openbackupsaas.room.domain.RoomType;

import java.util.UUID;

/**
 * 房型对外投影。不含 {@code tenantId}/{@code hotelId}：它们已经在路径里，
 * 重复回显只会让调用方误以为可以靠改响应里的值来切换归属。
 */
public record RoomTypeView(
        UUID id,
        String code,
        String name,
        Integer capacityAdults,
        Integer capacityChildren,
        String bedType,
        String status,
        Long version
) {

    public static RoomTypeView of(RoomType roomType) {
        return new RoomTypeView(roomType.id(), roomType.code(), roomType.name(),
                roomType.capacityAdults(), roomType.capacityChildren(), roomType.bedType(),
                roomType.status().name(), roomType.version());
    }
}
