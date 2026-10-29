package cn.sec.hn.openbackupsaas.tenant.domain;

/**
 * 租户状态。语义见 docs/requirements/scope.md §4.1 与迁移 V1 注释。
 *
 * <p>{@code SUSPENDED} 表示停止写入（不删数据）；{@code CLOSED} 表示关停、只读。
 * 状态对写入的影响在服务层显式判断，不依赖前端或调用方自觉。
 */
public enum TenantStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED;

    public boolean allowsWrite() {
        return this == ACTIVE;
    }
}
