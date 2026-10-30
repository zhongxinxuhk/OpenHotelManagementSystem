package cn.sec.hn.openbackupsaas.reservation.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 订单列表查询条件（S1-11）。
 *
 * <p><b>为什么把条件收成一个对象：</b>过滤项会继续长（渠道、住客、金额），
 * 散成七八个方法参数之后，每加一个都要改签名与全部调用点；而且"哪些条件允许为 null"
 * 会变成只能从 SQL 里反推的知识。
 *
 * <p><b>分页用 offset/limit 而不是游标：</b>本系统的订单列表按创建时间倒序、数据量以单店日增几十单计，
 * offset 足够；真正的关键是<b>排序必须唯一</b>——只按 {@code created_at} 排序时，
 * 同一毫秒创建的两单在不同页之间可能重复或漏掉。因此排序固定为
 * {@code created_at DESC, id DESC}，且<b>不接受客户端自定义排序</b>（也就没有排序注入面）。
 *
 * <p>校验放在紧凑构造器里：非法的 page/size 属于调用方输入问题，
 * 在进入 SQL 之前就以 400 拒绝，而不是让数据库去 LIMIT 一个负数。
 */
public record ReservationQuery(
        UUID tenantId,
        UUID hotelId,
        List<ReservationStatus> statuses,
        ReservationSourceType sourceType,
        String reservationNoPrefix,
        String roomCode,
        LocalDate stayFrom,
        LocalDate stayTo,
        int page,
        int size
) {

    /** 单页上限：防止一次拉全表，也防止 size=100000 把数据库拖死。 */
    public static final int MAX_PAGE_SIZE = 100;

    public ReservationQuery {
        Objects.requireNonNull(tenantId, "查询必须限定租户");
        Objects.requireNonNull(hotelId, "查询必须限定门店");
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        // 订单号按前缀 LIKE 匹配，必须转义 % _ \ ——否则用户输入一个 % 就等于把前缀条件去掉，
        // 变成全店扫描。订单号格式本身是 [A-Za-z0-9-]，转义不会让正常输入失真。
        reservationNoPrefix = escapeLike(reservationNoPrefix);
        if (page < 1) {
            throw new IllegalArgumentException("page 从 1 开始");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size 必须在 1—" + MAX_PAGE_SIZE + " 之间");
        }
        if ((stayFrom == null) != (stayTo == null)) {
            throw new IllegalArgumentException("入住区间必须同时给出起止日");
        }
        if (stayFrom != null && !stayTo.isAfter(stayFrom)) {
            throw new IllegalArgumentException("区间结束日必须晚于开始日");
        }
    }

    /** 与 SQL 里的 {@code ESCAPE '\'} 对应。 */
    private static String escapeLike(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    public int offset() {
        return (page - 1) * size;
    }

    /** 是否带入住区间过滤：SQL 里要按 daterange 交集判断，不能按"单点落在区间内"。 */
    public boolean hasStayFilter() {
        return stayFrom != null;
    }

    /** 订单号按前缀匹配：精确或前缀都能用上唯一索引，中间匹配必然全表扫。 */
    public boolean hasReservationNoFilter() {
        return reservationNoPrefix != null && !reservationNoPrefix.isBlank();
    }

    // 注：这里不需要为 XML 里的 #{q.tenantId} 额外写 getXxx()。
    // MyBatis 3.5.19（本项目实际版本）的 Reflector 已能识别 record 的访问器方法，
    // 这一点用反向验证确认过：临时删掉全部 getter 后，查询用例仍然通过。
    // 记录在此是为了避免以后有人"凭印象"加回一堆无用的转发方法。
}
