package cn.sec.hn.openbackupsaas.reconcile.domain;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 一次对账批次的记录（S2-05）。
 *
 * <p>保留 {@code status} 与 {@code error}：批次失败时不能只写日志——否则看板上
 * "最近一次对账"仍然是三天前那个绿色的成功批次，没人知道对账已经坏了三天。
 */
public record ReconcileRunView(
        UUID runId,
        UUID hotelId,
        LocalDate windowFrom,
        LocalDate windowTo,
        String triggerSource,
        String status,
        int newFindings,
        int repeatedFindings,
        int repairedFindings,
        int supersededFindings,
        int openFindings,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String error
) {
}
