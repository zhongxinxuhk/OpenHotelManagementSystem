package cn.sec.hn.openbackupsaas.platform.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 平台会话（ADR-019 §2.2）。
 *
 * <p>与住客会话同理：只记"这次签发了什么"，动作集合每次请求现读，
 * 所以撤动作下一个请求就生效，不需要失效广播。
 */
public record PlatformSession(
        UUID id,
        UUID userId,
        String tokenHash,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt,
        String revokeReason
) {

    public static PlatformSession issue(UUID id,
                                        UUID userId,
                                        String tokenHash,
                                        OffsetDateTime issuedAt,
                                        OffsetDateTime expiresAt) {
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("会话过期时间必须晚于签发时间");
        }
        return new PlatformSession(id, userId, tokenHash, issuedAt, expiresAt, null, null);
    }
}
