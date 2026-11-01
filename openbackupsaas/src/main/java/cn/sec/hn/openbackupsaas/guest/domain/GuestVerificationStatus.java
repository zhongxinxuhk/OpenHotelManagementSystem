package cn.sec.hn.openbackupsaas.guest.domain;

/**
 * 实名状态（ADR-018 §2.5）。
 *
 * <p>库内只存 {@link #PENDING} / {@link #VERIFIED} / {@link #REJECTED} 三种；
 * {@link #UNVERIFIED} 是"没有实名档案"的**计算态**，不落库——
 * 如果既存"没有记录"又存"状态=未实名"，两处真相迟早不一致。
 */
public enum GuestVerificationStatus {

    /** 没有提交过实名资料（不落库，由"查不到档案"推导）。 */
    UNVERIFIED,

    /** 已提交，等待核验。核验渠道未定（ADR-018 §6），所以当前真实状态停在这里。 */
    PENDING,

    /** 已核验通过。**不允许在没有核验渠道的情况下伪造这个状态**。 */
    VERIFIED,

    /** 核验不通过（信息不符、证件过期、风控拒绝）。 */
    REJECTED;

    /** 是否已满足"使用其它功能的前置条件"。只有 VERIFIED 算。 */
    public boolean satisfiesRealNameRequirement() {
        return this == VERIFIED;
    }
}
