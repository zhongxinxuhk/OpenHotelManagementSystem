package cn.sec.hn.openbackupsaas.reservation.domain;

/**
 * 占用种类。预订占用与封房（含维修）**同表共存**，共享同一条排斥约束：
 * 分成两张表就会出现"维修与预订可以同时拿到同一间房"的缝。
 */
public enum OccupancyKind {
    RESERVATION,
    BLOCK
}
