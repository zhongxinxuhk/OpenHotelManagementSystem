package cn.sec.hn.openbackupsaas.shared.security;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 已认证主体，放进 Spring Security 的 {@code Authentication}。
 *
 * <p>与 {@link TenantContext} 的分工：本记录是"认证结果"（含会话标识与过期时间，
 * 供登出/改密定位会话），{@code TenantContext} 是"授权与数据范围"（供领域层使用）。
 * 两者都只由服务端会话解析得出，不来自请求参数。
 *
 * <p>{@code mustChangePassword} 表示"这个账号还在用管理员发的初始口令"。
 * 为真时只允许改密、看自己是谁、登出，其余业务一律拒绝
 * （见 {@code PasswordChangeRequiredInterceptor}）。放在这里而不是每次查库，
 * 是因为它随会话一起解析出来，业务入口拿不到"漏查"的机会。
 */
public record PmsPrincipal(
        UUID sessionId,
        UUID tenantId,
        UUID userId,
        String username,
        String displayName,
        OffsetDateTime sessionExpiresAt,
        Set<UUID> hotelIds,
        Set<String> permissions,
        boolean mustChangePassword
) implements Principal {

    @Override
    public String getName() {
        return username;
    }

    public TenantContext toTenantContext() {
        return new TenantContext(tenantId, userId, username, hotelIds, permissions);
    }
}
