package cn.sec.hn.openbackupsaas.room.domain;

/**
 * 房务（清洁）状态（S1-04 落字段，S3-06 落流转）。
 *
 * <p>**与销售事实解耦**：本枚举的任何变化都不得改写占用、库存流水或房态投影。
 * 本轮只建列与默认值，不提供写入口，正是为了避免"顺手在房间更新接口里带上清洁状态"
 * 这类把两条事实混在一起的做法（验收项：清洁变化不直接改写库存事实）。
 */
public enum HousekeepingStatus {
    DIRTY,
    CLEAN,
    INSPECTED
}
