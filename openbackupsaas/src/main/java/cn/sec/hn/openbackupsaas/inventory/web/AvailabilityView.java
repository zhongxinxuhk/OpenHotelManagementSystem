package cn.sec.hn.openbackupsaas.inventory.web;

import cn.sec.hn.openbackupsaas.inventory.domain.BookableRoomType;
import cn.sec.hn.openbackupsaas.inventory.domain.InventoryDay;

import java.time.LocalDate;
import java.util.List;

/**
 * 可售查询响应（S1-12 / D-01）。
 *
 * <p><b>为什么把"每日余量"和"整段可售"一起返回：</b>它们是同一个问题的两个答案维度，
 * 分开两个接口会让调用方必须发两次请求、还要自己保证两次之间没有新订单改变结论。
 * 一次返回，前端就不必在"日历显示还剩 1 间"和"下单被拒"之间做无解的解释。
 *
 * <p>{@code bookable} 里没有出现的房型，表示该房型整段<strong>不可售</strong>
 * （没有任何一间房能连住），而不是"没数据"——所以调用方要按 {@code bookableRooms > 0} 判断，
 * 不能拿 {@code days} 里的最小值代替。
 */
public record AvailabilityView(
        LocalDate from,
        LocalDate to,
        int nights,
        List<InventoryDay> days,
        List<BookableRoomType> bookable
) {
}
