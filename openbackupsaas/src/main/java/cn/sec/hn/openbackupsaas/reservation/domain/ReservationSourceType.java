package cn.sec.hn.openbackupsaas.reservation.domain;

/** 订单来源（D-12）。渠道订单由 L 域写入，不凭外部单号判定唯一性。 */
public enum ReservationSourceType {
    FRONTDESK,
    DIRECT,
    OTA,
    MINIPROGRAM
}
