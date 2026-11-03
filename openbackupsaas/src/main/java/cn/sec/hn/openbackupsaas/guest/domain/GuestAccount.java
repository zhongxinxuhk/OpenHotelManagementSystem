package cn.sec.hn.openbackupsaas.guest.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 住客主身份（ADR-018 §2.2）。
 *
 * <p><b>无 tenant_id</b>：住客不属于任何租户。这是全库唯一一类没有租户列的业务表，
 * 所以配套约束见 {@code V14} 迁移的注释：跨平面只许单向引用（租户表可以引用本表 id，
 * 本表不得引用任何租户业务表）。
 *
 * <p>{@code id} 由服务端生成且**永不变**：订单、发票、审计、对公账单都指向它。
 * 客户端**不感知**这个 UUID——对外一律用不可猜测的引用，绝不用手机号或身份证号作标识。
 */
public record GuestAccount(
        UUID id,
        String displayName,
        GuestAccountStatus status,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static GuestAccount create(UUID id, String displayName, OffsetDateTime now) {
        return new GuestAccount(id, displayName, GuestAccountStatus.ACTIVE, 0L, now, now);
    }
}
