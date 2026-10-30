package cn.sec.hn.openbackupsaas.reservation.domain;

/**
 * 订单聚合状态（S0-02 候选，与迁移 V4 的 CHECK 一致）。
 *
 * <p>{@code NO_SHOW} 于 S1-09 正式启用：V4 注释已预留"定案后追加（只改 CHECK）"，
 * 对应 V6_2 迁移扩展 {@code ck_reservation_status}。是否计费的规则仍待 DEC-08，
 * 但状态迁移本身（已确认 → No-show → 释放占用）属 S1-09 交付范围。
 */
public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    CHECKED_IN,
    CHECKED_OUT,
    CANCELLED,
    EXPIRED,
    NO_SHOW
}
