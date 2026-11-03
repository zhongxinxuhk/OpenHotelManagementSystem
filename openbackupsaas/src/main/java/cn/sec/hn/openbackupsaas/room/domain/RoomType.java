package cn.sec.hn.openbackupsaas.room.domain;

import cn.sec.hn.openbackupsaas.tenant.domain.OrgStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 房型（门店内的销售单位，S1-04）。
 *
 * <p>{@code capacityAdults}/{@code capacityChildren} 用 {@link Integer} 而不是 {@code short}：
 * PG 的 SMALLINT 走 JDBC 的 {@code getInt}，MyBatis 构造映射要求与 {@code java.lang.Integer} 精确匹配
 * （S1-02 已踩过 {@code long}/{@code Long} 的同类坑）。
 *
 * <p>**故意没有**"可售数量/配额"字段：DEC-05（真实未分房配额）未定案，
 * 首期按实体房分配，配额模型定案后再开迁移，不在这里塞可空列当占位。
 */
public record RoomType(
        UUID id,
        UUID tenantId,
        UUID hotelId,
        String code,
        String name,
        Integer capacityAdults,
        Integer capacityChildren,
        String bedType,
        OrgStatus status,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static RoomType create(UUID id,
                                  UUID tenantId,
                                  UUID hotelId,
                                  String code,
                                  String name,
                                  int capacityAdults,
                                  int capacityChildren,
                                  String bedType,
                                  OffsetDateTime now) {
        return new RoomType(id, tenantId, hotelId, code, name, capacityAdults, capacityChildren,
                bedType, OrgStatus.ACTIVE, 0L, now, now);
    }
}
