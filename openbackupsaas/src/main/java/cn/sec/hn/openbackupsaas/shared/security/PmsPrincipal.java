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
 */
public record PmsPrincipal(
        UUID sessionId,
        UUID tenantId,
        UUID userId,
        String username,
        String displayName,
        OffsetDateTime sessionExpiresAt,
        Set<UUID> hotelIds,
        Set<String> permissions
) implements Principal {

    @Override
    public String getName() {
        return username;
    }

    public TenantContext toTenantContext() {
        return new TenantContext(tenantId, userId, username, hotelIds, permissions);
    }
}
