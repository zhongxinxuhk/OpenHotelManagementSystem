package cn.sec.hn.openbackupsaas.guest.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 忘记密码**重置挑战**的读取记录（R-20261103-01）。
 *
 * <p>第三张挑战表（绑定 {@link OwnershipChallenge} / 登录 {@link LoginChallenge} /
 * 重置本记录）。分表理由除"可用性不联动"（重置不受 EMAIL 登录开关约束——
 * 账号恢复 ≠ 登录方式）外，本表多一条：**码按用途绑定**——重置挑战的码
 * 只能完成重置口令，不能登录；登录挑战的码也不能重置口令。
 * 共用一张表时，攻击者发起"登录挑战"后诱骗用户读出邮件里的码
 * （邮件写的是"登录验证码"），即可拿去重置口令——用户对"我在登录"
 * 与"我在改密码"的心智警觉完全不同。
 *
 * <p>防爆破三件套与前两张完全对齐：SHA-256 哈希存储（{@code codeHash}
 * 绝不进响应或日志）、{@value #MAX_ATTEMPTS} 次尝试上限、
 * {@value #TTL_MINUTES} 分钟过期、一次性消费。
 */
public record PasswordResetChallenge(
        UUID id,
        UUID accountId,
        String provider,
        String subject,
        String codeHash,
        OffsetDateTime expiresAt,
        int attempts,
        OffsetDateTime consumedAt
) {

    /** 防爆破的尝试上限。与前两张挑战表同值。 */
    public static final int MAX_ATTEMPTS = 5;

    /** 挑战有效期（分钟）。 */
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
