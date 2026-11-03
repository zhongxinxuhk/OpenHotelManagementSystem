package cn.sec.hn.openbackupsaas.journal.domain;

import java.util.Set;

/**
 * 审计动作目录（S1-06）。
 *
 * <p>动作名必须来自这里，不能在各处写字符串字面量：
 * 审计是给人和对账脚本读的，同一个动作出现两种写法就等于没有审计。
 * 命名规则为小写点分（{@code 领域.动作}），与数据库 CHECK 一致。
 */
public final class AuditActions {

    public static final String OCCUPANCY_HELD = "occupancy.hold";
    public static final String OCCUPANCY_BLOCKED = "occupancy.block";
    public static final String OCCUPANCY_RELEASED = "occupancy.release";
    public static final String OCCUPANCY_UNBLOCKED = "occupancy.unblock";
    public static final String RESERVATION_CREATED = "reservation.create";
    public static final String RESERVATION_CONFIRMED = "reservation.confirm";
    public static final String RESERVATION_CANCELLED = "reservation.cancel";
    public static final String RESERVATION_NO_SHOW = "reservation.no_show";
    /** 锁房超时整单释放（S1-10）：与人工取消区分开，运维按这个动作名就能查出"被时间放掉的单"。 */
    public static final String RESERVATION_EXPIRED = "reservation.expire";

    private static final Set<String> KNOWN =
            Set.of(OCCUPANCY_HELD, OCCUPANCY_BLOCKED, OCCUPANCY_RELEASED, OCCUPANCY_UNBLOCKED,
                    RESERVATION_CREATED, RESERVATION_CONFIRMED, RESERVATION_CANCELLED, RESERVATION_NO_SHOW,
                    RESERVATION_EXPIRED);

    private AuditActions() {
    }

    /** 新增动作必须同步登记，避免"写进库了但目录里没有"的静默漂移。 */
    public static boolean isKnown(String action) {
        return KNOWN.contains(action);
    }

    public static Set<String> known() {
        return KNOWN;
    }
}
