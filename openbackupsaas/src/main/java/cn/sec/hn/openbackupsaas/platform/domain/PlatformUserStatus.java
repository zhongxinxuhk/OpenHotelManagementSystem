package cn.sec.hn.openbackupsaas.platform.domain;

/** 平台账号状态。与员工账号同样的两态，理由相同：停用要立即生效，不做"冻结中"这类中间态。 */
public enum PlatformUserStatus {
    ACTIVE,
    DISABLED
}
