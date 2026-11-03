package cn.sec.hn.openbackupsaas.reservation.domain;

/** 订单行状态（S0-02 候选；换房/部分履约的允许路径受 DEC-04 约束，未定案前不实现）。 */
public enum LineStatus {
    PENDING,
    CONFIRMED,
    CHECKED_IN,
    CHECKED_OUT,
    CANCELLED,
    EXPIRED,
    NO_SHOW
}
