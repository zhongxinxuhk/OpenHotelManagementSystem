package cn.sec.hn.openbackupsaas.guest.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 验证码**登录**挑战的读取记录（R-20261102-07）。
 *
 * <p>与 {@link OwnershipChallenge}（绑定用）分表分模型——登录挑战是
 * "身份证明"（匿名发起、凭证定位账户），绑定挑战是"归属验证"
 * （已登录发起、挑战绑定账户）。差异集中在一处：{@code accountId 可空}
 * ——登录前没有身份，凭证定位发生在码校验通过之后。
 *
 * <p>防爆破三件套与绑定挑战**完全对齐**：SHA-256 哈希存储
 * （{@code codeHash} 供比对，绝不进响应或日志——那会把"哈希 + 有限尝试"
 * 的防线降级成离线爆破）、{@value #MAX_ATTEMPTS} 次尝试上限、
 * {@value #TTL_MINUTES} 分钟过期、一次性消费。
 */
public record LoginChallenge(
        UUID id,
        UUID accountId,
        String provider,
        String subject,
        String codeHash,
        OffsetDateTime expiresAt,
        int attempts,
        OffsetDateTime consumedAt
) {

    /** 防爆破的尝试上限。与绑定挑战同值：6 位数字熵低，没有它等于允许在线爆破。 */
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
