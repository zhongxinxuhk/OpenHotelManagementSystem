package cn.sec.hn.openbackupsaas.reservation.domain;

import java.util.List;

/**
 * 订单状态机迁移守卫（S1-09）。纯函数，不碰数据库，便于无库单测。
 *
 * <p>首期只实现"整单统一迁移"（DEC-04 部分履约 / 按房履约仍 OPEN，见 S0-02 §5.1），
 * 故守卫基于"订单所有订单行当前状态"判断，而不是单条 L。命令层据此在事务内锁定聚合根、
 * 复核版本，再统一推进 L / A / O。
 *
 * <p>返回 {@code null} 表示合法迁移；返回非空字符串为拒绝原因（业务层抛 409）。
 */
public final class ReservationTransitions {

    private ReservationTransitions() {
    }

    /** 确认：要求所有订单行处于 PENDING（对应占用 HELD）。 */
    public static String confirmError(List<LineStatus> lines) {
        return requireAllIn(lines, List.of(LineStatus.PENDING),
                "确认要求订单处于待确认（PENDING）");
    }

    /**
     * 取消：要求所有订单行处于 PENDING 或 CONFIRMED；已入住 / 已离店 / 已终态一律拒绝。
     *
     * <p>特别地：已入住（CHECKED_IN）不能经普通取消接口直接释放——那是 S0-02 §6.1 与 S1-09 验收
     * 明确禁止的"快捷迁移"，必须走离店 / 账务 / 人工协调流程。
     */
    public static String cancelError(List<LineStatus> lines) {
        if (lines == null || lines.isEmpty()) {
            return "订单没有任何订单行";
        }
        for (LineStatus s : lines) {
            switch (s) {
                case PENDING, CONFIRMED -> {
                    // 合法来源，继续检查下一行
                }
                case CHECKED_IN -> {
                    return "已入住的订单不能经普通取消接口直接释放，须走离店 / 账务流程";
                }
                case CHECKED_OUT -> {
                    return "已离店的订单不可取消";
                }
                case CANCELLED -> {
                    return "订单已取消";
                }
                case EXPIRED -> {
                    return "订单已过期";
                }
                case NO_SHOW -> {
                    return "订单已记为 No-show";
                }
            }
        }
        return null;
    }

    /** No-show：要求所有订单行处于 CONFIRMED（已确认且从未入住）。 */
    public static String noShowError(List<LineStatus> lines) {
        return requireAllIn(lines, List.of(LineStatus.CONFIRMED),
                "No-show 要求订单已确认（CONFIRMED）且从未入住");
    }

    private static String requireAllIn(List<LineStatus> lines, List<LineStatus> allowed, String base) {
        if (lines == null || lines.isEmpty()) {
            return "订单没有任何订单行";
        }
        for (LineStatus s : lines) {
            if (!allowed.contains(s)) {
                return base + "，但存在状态为 " + s + " 的订单行";
            }
        }
        return null;
    }
}
