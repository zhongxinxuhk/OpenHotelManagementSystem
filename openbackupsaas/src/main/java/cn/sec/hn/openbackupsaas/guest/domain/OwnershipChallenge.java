package cn.sec.hn.openbackupsaas.guest.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 归属挑战的读取记录（ADR-019 §2.10）。
 *
 * <p>**没有验证码明文**：库里只有 SHA-256 哈希（防线 = 哈希 + 尝试上限 + 短过期三件套）。
 * {@code codeHash} 供服务层比对用——它是单向哈希，外发无意义，但**绝不能**进任何
 * HTTP 响应或日志：那会把"哈希 + 有限尝试"的防线降级成离线爆破。
 */
public record OwnershipChallenge(
        UUID id,
        UUID accountId,
        String provider,
        String subject,
        String codeHash,
        OffsetDateTime expiresAt,
        int attempts,
        OffsetDateTime consumedAt
) {

    /** 防爆破的尝试上限。6 位数字熵低，没有这个上限等于允许 10^6 次在线爆破。 */
    public static final int MAX_ATTEMPTS = 5;

    /** 挑战有效期（分钟）。与尝试上限共同构成防线。 */
    public static final int TTL_MINUTES = 10;

    public boolean isExpiredAt(OffsetDateTime moment) {
        return !expiresAt.isAfter(moment);
    }

    public boolean isExhausted() {
        return attempts >= MAX_ATTEMPTS;
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }
}
