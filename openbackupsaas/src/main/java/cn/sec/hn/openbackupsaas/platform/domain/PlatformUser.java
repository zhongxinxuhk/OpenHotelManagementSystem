package cn.sec.hn.openbackupsaas.platform.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 平台雇员（ADR-017 §2.1、ADR-019 §2.1）。
 *
 * <p><b>无 tenant_id，与 {@code AppUser} 完全隔离</b>。不复用 {@code app_user} 的理由：
 * 那张表的 {@code tenant_id} 是 NOT NULL 且带复合外键，一旦放宽成可空，
 * 所有租户内查询都要写 {@code tenant_id = ? OR tenant_id IS NULL}——
 * 隔离从"编译期保证"退化成"每个查询记得写"。伪造一个"平台租户"更糟：
 * 平台账号会被租户边界挡住，而租户列表里多出一个假租户。
 *
 * <p>{@code mustChangePassword} 与员工账号同义：初始口令必须首次登录就换掉。
 */
public record PlatformUser(
        UUID id,
        String username,
        String displayName,
        String passwordHash,
        PlatformUserStatus status,
        boolean mustChangePassword,
        OffsetDateTime lastLoginAt,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static PlatformUser create(UUID id,
                                      String username,
                                      String displayName,
                                      String passwordHash,
                                      boolean mustChangePassword,
                                      OffsetDateTime now) {
        return new PlatformUser(id, username, displayName, passwordHash,
                PlatformUserStatus.ACTIVE, mustChangePassword, null, 0L, now, now);
    }

    public boolean isActive() {
        return status == PlatformUserStatus.ACTIVE;
    }
}
