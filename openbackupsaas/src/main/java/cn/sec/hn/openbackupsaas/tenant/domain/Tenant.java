package cn.sec.hn.openbackupsaas.tenant.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 租户（签约经营主体，数据隔离边界）。
 *
 * <p>不可变记录：状态变更通过显式命令 + 乐观锁版本号完成，不做 setter 式就地修改，
 * 避免"读到旧对象改一半"的隐式覆盖。
 *
 * <p>{@code version} 用包装类型 {@link Long} 而非 {@code long}：MyBatis 构造参数映射
 * 需要与列类型（BIGINT → java.lang.Long）精确匹配，基元类型找不到对应构造器。数据库列为 NOT NULL，
 * 因此实际不会为 null。
 */
public record Tenant(
        UUID id,
        String code,
        String name,
        TenantStatus status,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /** 新建租户：状态固定 ACTIVE、版本 0，时间戳由服务端生成。 */
    public static Tenant create(UUID id, String code, String name, OffsetDateTime now) {
        return new Tenant(id, code, name, TenantStatus.ACTIVE, 0L, now, now);
    }
}
