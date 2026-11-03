package cn.sec.hn.openbackupsaas.inventory.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * 可售查询窗口（S1-12）。
 *
 * <p><b>为什么强制窗口上限：</b>可售计算要按"房间 × 每晚"展开并逐格判断占用，
 * 时间跨度直接决定计算量。放开成"随便查一年"，等于给数据库一个 O(房间数 × 365) 的
 * 交叉连接——单店一年就是几十万行，多店并发时会把连接池占满。
 *
 * <p>上限取 62 天（约两个月）不是拍脑袋：房态日历与渠道推送的实际作息都以"未来两个月"为界，
 * 更远的日期既没人看也没人卖。需要更长的报表请走 S3-09 的异步导出，而不是在这里放大参数。
 *
 * <p>允许查历史窗口：对账、追责、客诉都要看过去。只限长度，不限方向。
 */
public record InventoryWindow(
        UUID tenantId,
        UUID hotelId,
        UUID roomTypeId,
        LocalDate from,
        LocalDate to
) {

    /** 单次查询允许的最大天数（半开区间 [from, to) 的跨度）。 */
    public static final int MAX_WINDOW_DAYS = 62;

    public InventoryWindow {
        Objects.requireNonNull(tenantId, "查询必须限定租户");
        Objects.requireNonNull(hotelId, "查询必须限定门店");
        Objects.requireNonNull(from, "必须给出起始日期");
        Objects.requireNonNull(to, "必须给出结束日期");
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("结束日期必须晚于起始日期：" + from + " → " + to);
        }
        long days = ChronoUnit.DAYS.between(from, to);
        if (days > MAX_WINDOW_DAYS) {
            throw new IllegalArgumentException("查询窗口最多 " + MAX_WINDOW_DAYS + " 天，当前 " + days + " 天");
        }
    }

    public int nights() {
        return (int) ChronoUnit.DAYS.between(from, to);
    }

    public boolean hasRoomTypeFilter() {
        return roomTypeId != null;
    }
}
