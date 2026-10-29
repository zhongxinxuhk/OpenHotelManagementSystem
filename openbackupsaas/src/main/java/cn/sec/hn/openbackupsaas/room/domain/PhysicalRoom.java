package cn.sec.hn.openbackupsaas.room.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 实体房间（可被占用的最小资源单位，S1-04）。
 *
 * <p>与房型的归属关系在数据库层用 {@code (room_type_id, hotel_id)} 复合外键保证同店，
 * 应用层再校验一次只是为了给出可读的错误，不承担正确性。
 */
public record PhysicalRoom(
        UUID id,
        UUID tenantId,
        UUID hotelId,
        UUID roomTypeId,
        String code,
        Integer floor,
        RoomStatus status,
        HousekeepingStatus housekeepingStatus,
        String notes,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /** 新房间：运营状态 ACTIVE、房务状态 DIRTY（还没打扫过）。 */
    public static PhysicalRoom create(UUID id,
                                      UUID tenantId,
                                      UUID hotelId,
                                      UUID roomTypeId,
                                      String code,
                                      Integer floor,
                                      String notes,
                                      OffsetDateTime now) {
        return new PhysicalRoom(id, tenantId, hotelId, roomTypeId, code, floor,
                RoomStatus.ACTIVE, HousekeepingStatus.DIRTY, notes, 0L, now, now);
    }
}
