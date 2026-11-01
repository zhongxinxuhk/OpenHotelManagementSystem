package cn.sec.hn.openbackupsaas.guest.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 住客会话记录（ADR-019 §2.2）。
 *
 * <p>只承载"这次签发了什么"，**不缓存实名状态或任何身份结论**：
 * 那些每次请求从库里现读，所以人工审核通过后下一次请求就生效，不需要失效广播。
 *
 * <p>{@code tokenHash} 是带住客域前缀的 SHA-256（见 {@code GuestSessionTokens}）；
 * 明文令牌不进入本对象，也不落库。
 */
public record GuestSession(
        UUID id,
        UUID accountId,
        String tokenHash,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt,
        String revokeReason
) {

    public static GuestSession issue(UUID id,
                                     UUID accountId,
                                     String tokenHash,
                                     OffsetDateTime issuedAt,
                                     OffsetDateTime expiresAt) {
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("会话过期时间必须晚于签发时间");
        }
        return new GuestSession(id, accountId, tokenHash, issuedAt, expiresAt, null, null);
    }

    public boolean isUsableAt(OffsetDateTime moment) {
        return revokedAt == null && expiresAt.isAfter(moment);
    }
}
