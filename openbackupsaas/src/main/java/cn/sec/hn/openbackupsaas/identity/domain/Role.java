package cn.sec.hn.openbackupsaas.identity.domain;

import cn.sec.hn.openbackupsaas.tenant.domain.OrgStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 角色（权限点的集合）。状态复用 {@link OrgStatus}：停用角色不再生效，
 * 但已授予关系保留，便于恢复。
 */
public record Role(
        UUID id,
        UUID tenantId,
        String code,
        String name,
        OrgStatus status,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static Role create(UUID id, UUID tenantId, String code, String name, OffsetDateTime now) {
        return new Role(id, tenantId, code, name, OrgStatus.ACTIVE, 0L, now, now);
    }
}
