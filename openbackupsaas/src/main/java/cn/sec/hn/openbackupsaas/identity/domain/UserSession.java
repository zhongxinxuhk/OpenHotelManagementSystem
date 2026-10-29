package cn.sec.hn.openbackupsaas.identity.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 服务端会话记录（S1-03）。
 *
 * <p>只承载"这次登录签发了什么"，**不含**身份快照：权限与门店范围每次请求都从库里现读，
 * 这样撤权、调岗、门店调整在下一个请求就生效，不需要额外的失效广播。
 *
 * <p>{@code tokenHash} 是令牌的 SHA-256 十六进制；明文令牌不进入本对象。
 */
public record UserSession(
        UUID id,
        UUID tenantId,
        UUID userId,
        String tokenHash,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt,
        String revokeReason
) {

    public static UserSession issue(UUID id,
                                    UUID tenantId,
                                    UUID userId,
                                    String tokenHash,
                                    OffsetDateTime issuedAt,
                                    OffsetDateTime expiresAt) {
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("会话过期时间必须晚于签发时间");
        }
        return new UserSession(id, tenantId, userId, tokenHash, issuedAt, expiresAt, null, null);
    }

    public boolean isUsableAt(OffsetDateTime moment) {
        return revokedAt == null && expiresAt.isAfter(moment);
    }
}
