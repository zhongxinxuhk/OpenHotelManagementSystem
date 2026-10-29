package cn.sec.hn.openbackupsaas.reservation.domain;

/**
 * 订单聚合状态（S0-02 候选，与迁移 V4 的 CHECK 一致）。
 *
 * <p>取值随 S0-02 定案调整：新增值需要同步迁移与本枚举。**NO_SHOW 待 D-07/DEC-08**，
 * 在那之前不把"是否计费"的规则写进状态语义。
 */
public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    CHECKED_IN,
    CHECKED_OUT,
    CANCELLED,
    EXPIRED
}
