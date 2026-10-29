package cn.sec.hn.openbackupsaas.identity.domain;

/**
 * 员工账号状态（S1-03）。
 *
 * <p>{@code DISABLED} 的语义是"立即不能登录、已有会话在下次请求时被拒"，
 * 不是"删除"：历史操作记录仍要能追溯到这个人，因此不做物理删除。
 */
public enum UserStatus {
    ACTIVE,
    DISABLED;

    public boolean canLogin() {
        return this == ACTIVE;
    }
}
