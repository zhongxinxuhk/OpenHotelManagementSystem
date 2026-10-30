package cn.sec.hn.openbackupsaas.reconcile.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 一轮对账的结果（S2-05）。
 *
 * <p>字段刻意区分「新发现」「重复出现」「已修复」「已退场」四类，而不是只给一个
 * "openFindings"：运维真正想知道的是<b>变化</b>——新增 3 条意味着刚才出了新问题，
 * 重复 3 条意味着老问题还没人管。只报总数，两种情况看起来一模一样。
 *
 * @param repaired 本轮自动修复成功的差异（只可能是 {@link FindingSeverity#DERIVED}）
 */
public record ReconcileRunReport(
        UUID runId,
        UUID hotelId,
        LocalDate windowFrom,
        LocalDate windowTo,
        String triggerSource,
        int newFindings,
        int repeatedFindings,
        int repairedFindings,
        int supersededFindings,
        int openFindings,
        int openDerived,
        int openFact,
        int openBacklog
) {

    /** 本轮是否需要人看。派生类可以自己修，事实类与积压类不行。 */
    public boolean needsAttention() {
        return openFact > 0 || openBacklog > 0;
    }
}
