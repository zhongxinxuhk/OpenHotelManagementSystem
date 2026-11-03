package cn.sec.hn.openbackupsaas.shared.web;

import java.util.List;

/**
 * 分页结果信封（S1-11）。
 *
 * <p><b>为什么固定返回 total：</b>列表页要显示"共 N 条 / 第 x 页"，
 * 让前端或客户端自己再发一次 count 请求，等于把一致性问题外包给调用方：
 * 两次请求之间的新增订单会让页码与总数互相矛盾。
 *
 * <p><b>为什么 total 是 long：</b>订单表将来会到千万级，int 在某天会静默溢出成负数。
 *
 * <p>估算页码在服务端算好：客户端不该重复实现一次"向上取整"并各自差一。
 */
public record PageView<T>(List<T> items, int page, int size, long total, int totalPages) {

    public static <T> PageView<T> of(List<T> items, int page, int size, long total) {
        int totalPages = size <= 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageView<>(List.copyOf(items), page, size, total, totalPages);
    }
}
