package cn.sec.hn.openbackupsaas.tenant.domain;

/**
 * 集团与门店的状态。{@code INACTIVE} 表示停用：不再接受新业务，
 * 但历史订单与报表仍需可查，因此不做物理删除。
 */
public enum OrgStatus {
    ACTIVE,
    INACTIVE;

    public boolean allowsWrite() {
        return this == ACTIVE;
    }
}
