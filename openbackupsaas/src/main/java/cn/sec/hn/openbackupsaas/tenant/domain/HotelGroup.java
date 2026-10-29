package cn.sec.hn.openbackupsaas.tenant.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 集团：租户下的可选组织层级。编码在同一租户内唯一，跨租户可重名。
 */
public record HotelGroup(
        UUID id,
        UUID tenantId,
        String code,
        String name,
        OrgStatus status,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static HotelGroup create(UUID id, UUID tenantId, String code, String name, OffsetDateTime now) {
        return new HotelGroup(id, tenantId, code, name, OrgStatus.ACTIVE, 0L, now, now);
    }
}
