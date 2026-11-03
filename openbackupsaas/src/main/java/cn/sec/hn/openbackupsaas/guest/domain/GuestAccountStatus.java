package cn.sec.hn.openbackupsaas.guest.domain;

/**
 * 住客账户状态。
 *
 * <p>与实名状态是两件事：{@code CLOSED} 是账户注销/风控关闭；
 * 实名是否通过看 {@link GuestVerificationStatus}。混成一个枚举会让"已实名但被关闭"
 * 这种正常状态无处表达。
 */
public enum GuestAccountStatus {
    ACTIVE,
    CLOSED;

    public boolean canLogin() {
        return this == ACTIVE;
    }
}
