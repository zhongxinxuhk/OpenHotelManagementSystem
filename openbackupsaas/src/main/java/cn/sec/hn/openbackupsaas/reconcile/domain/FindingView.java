package cn.sec.hn.openbackupsaas.reconcile.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 库里的一张差异工单（S2-05）。
 *
 * <p>{@code occurrences} 不是日志级别的计数，而是判断"值不值得动手"的依据：
 * 只出现一次就消失的差异多半是扫描时的时间差；连续几十轮都在的才是真问题。
 *
 * <p>{@code settledBy} 与 {@code settleReason} 只在人工结案/忽略时填写。
 * 忽略必须带原因，这一条由数据库 CHECK 兜底——人不会每次都记得填，
 * 而"半年后没人说得清为什么被扔掉"是这类系统的通病。
 */
public record FindingView(
        UUID findingId,
        UUID tenantId,
        UUID hotelId,
        FindingKind kind,
        FindingSeverity severity,
        String targetKey,
        String detail,
        String status,
        int occurrences,
        OffsetDateTime firstSeenAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime repairedAt,
        OffsetDateTime settledAt,
        UUID settledBy,
        String settleReason,
        java.time.LocalDate stayDate,
        UUID roomId,
        UUID reservationId
) {

    /** 是否还挂在看板上。 */
    public boolean open() {
        return "OPEN".equals(status);
    }

    /** 这台机器能不能自己把它消掉。 */
    public boolean repairable() {
        return open() && kind.allowsAutomaticRepair();
    }
}
