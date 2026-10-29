package cn.sec.hn.openbackupsaas.reservation.service;

import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据库约束冲突 → 对外可读的冲突原因（S1-05）。
 *
 * <p>为什么需要它：占用冲突会被数据库拒绝成 {@code DataIntegrityViolationException}，
 * 但对外既不能回 SQL，也不能只说"数据冲突"——调用方需要一个能判断"要不要换房/改日期"的结论。
 *
 * <p>做法：只按**约束名**映射固定文案，不解析、不回显错误消息里的任何值。
 * PostgreSQL 的约束错误消息含约束名与键值；这里只取约束名，其余一律丢弃。
 * 完整错误码体系在 S1-11 冻结，本类只提供"能说人话"的那一层。
 */
public final class OccupancyConflictTranslator {

    /** 只匹配"违反 <类型> 约束 \"名字\""这一句，其它内容一概不取。 */
    private static final Pattern CONSTRAINT_PATTERN =
            Pattern.compile("violates (?:exclusion|unique|check|foreign key) constraint \"([^\"]+)\"");

    /** 约束名 → 对外结论。新增约束时**必须**同步这张表，否则会退回通用文案。 */
    private static final Map<String, String> MESSAGES = Map.of(
            "ex_room_occupancy_effective", "房间在所选日期已被占用或封锁",
            "uq_room_occupancy_active_line", "该订单行已经存在有效占用",
            "ck_room_occupancy_range", "占用区间不合法：必须是非空、有界的半开区间 [入住日,离店日)",
            "ck_reservation_line_range", "购买区间不合法：必须是非空、有界的半开区间 [入住日,离店日)",
            "ck_room_occupancy_kind", "占用种类与引用/状态不配套",
            "fk_room_occupancy_line", "占用引用的订单行不存在或不属于该门店",
            "fk_room_occupancy_room", "占用引用的房间不存在或不属于该门店",
            "uq_reservation_no", "订单号在该门店下已存在",
            "uq_reservation_line_no", "订单行号在该订单下重复"
    );

    private static final String GENERIC = "数据冲突：违反唯一性、归属或区间约束";

    private OccupancyConflictTranslator() {
    }

    /** 把冲突转成 409 的业务冲突；未知约束退回通用文案，绝不回显原始消息。 */
    public static ConflictException translate(DataIntegrityViolationException ex) {
        String constraint = constraintNameOf(ex);
        String message = constraint == null ? GENERIC : MESSAGES.getOrDefault(constraint, GENERIC);
        return new ConflictException(message);
    }

    /**
     * 从错误消息里取约束名。
     *
     * <p>为什么不用驱动的 {@code PSQLException#getServerErrorMessage()}：驱动是 {@code runtime} 作用域，
     * 编译期拿不到它的类。用正则只取"约束名"这一段，其余消息一律丢弃——
     * 既避免回显键值，也不把实现绑死在某个驱动版本上。
     */
    public static String constraintNameOf(Throwable ex) {
        Throwable root = ex;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root == null ? null : root.getMessage();
        if (message == null) {
            return null;
        }
        Matcher matcher = CONSTRAINT_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }
}
