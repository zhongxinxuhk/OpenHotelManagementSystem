package cn.sec.hn.openbackupsaas.room.domain;

/**
 * 房间运营状态（S1-04）。
 *
 * <p>只表达"这间房现在能不能被排房"，**不表达**清洁与维修：
 * <ul>
 *   <li>清洁状态是独立字段（{@code housekeeping_status}），改它不影响销售事实；</li>
 *   <li>维修是带时间区间的占用（{@code room_block}），归 S1-05，不能用本状态代替——
 *       一个永久状态无法表达"8 月 3 日至 8 月 5 日维修"。</li>
 * </ul>
 */
public enum RoomStatus {
    ACTIVE,
    INACTIVE;

    public boolean allowsSale() {
        return this == ACTIVE;
    }
}
