package cn.sec.hn.openbackupsaas.tenant.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 门店：实体房间、当地时区与日常运营归属。
 *
 * @param groupId  可为 null，表示直属租户的门店
 * @param timezone IANA 时区名，如 {@code Australia/Sydney}；营业日切分时间待 S0-05 定案后另加字段
 */
public record Hotel(
        UUID id,
        UUID tenantId,
        UUID groupId,
        String code,
        String name,
        String timezone,
        OrgStatus status,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static Hotel create(UUID id, UUID tenantId, UUID groupId, String code, String name,
                               String timezone, OffsetDateTime now) {
        return new Hotel(id, tenantId, groupId, code, name, timezone, OrgStatus.ACTIVE, 0L, now, now);
    }
}
