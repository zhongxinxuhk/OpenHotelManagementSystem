package cn.sec.hn.openbackupsaas.identity.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 员工账号（登录主体）。
 *
 * <p>{@code passwordHash} 只允许 BCrypt 派生值；该字段不会被任何响应 DTO 引用，
 * 泄漏路径只能来自日志或调试打印，因此实体本身不提供 {@code toString} 覆写以外的东西——
 * record 的自动 {@code toString} 会带上 passwordHash，**禁止直接打印本对象**。
 *
 * <p>{@code version} 用 {@link Long}：MyBatis 构造参数映射要求与 BIGINT 精确匹配（见 S1-02 记录）。
 */
public record AppUser(
        UUID id,
        UUID tenantId,
        String username,
        String displayName,
        String passwordHash,
        UserStatus status,
        Long version,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /** 新建账号：状态 ACTIVE、版本 0，从未登录。 */
    public static AppUser create(UUID id,
                                UUID tenantId,
                                String username,
                                String displayName,
                                String passwordHash,
                                OffsetDateTime now) {
        return new AppUser(id, tenantId, username, displayName, passwordHash, UserStatus.ACTIVE, 0L, null, now, now);
    }

    /** 防误打印：只给可安全外泄的字段。 */
    @Override
    public String toString() {
        return "AppUser[id=" + id + ", tenantId=" + tenantId + ", username=" + username
                + ", status=" + status + "]";
    }
}
