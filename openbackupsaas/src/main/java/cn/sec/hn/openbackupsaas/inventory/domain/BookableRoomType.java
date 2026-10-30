package cn.sec.hn.openbackupsaas.inventory.domain;

import java.util.UUID;

/**
 * 某房型在**整个窗口**内可连续卖出的房间数（S1-12）。
 *
 * <p>这个数与 {@link InventoryDay#availableRooms()} 的每日最小值<b>不是一回事</b>，
 * 这正是可售判断最容易错的地方：
 * <pre>
 *   8/1：01 房空、02 房被占  → 每晚都有余房
 *   8/2：01 房被占、02 房空  → 每晚都有余房
 *   但没有任何一间房能从 8/1 连着住到 8/3 → 整段不可售
 * </pre>
 * 只看每日余量最小值会得出"还剩 1 间"的错误结论，客人下单必然撞上占用冲突。
 * 因此这里的数字来自"整段区间都空闲的房间"计数：为 0 时不能承诺整段住宿。
 */
public record BookableRoomType(UUID roomTypeId, Integer bookableRooms) {

    public boolean bookable() {
        return bookableRooms != null && bookableRooms > 0;
    }
}
