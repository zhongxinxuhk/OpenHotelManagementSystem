package cn.sec.hn.openbackupsaas.reconcile.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 扫描器产出的原始差异（S2-05）。
 *
 * <p>与库里的工单（{@link FindingView}）分开：原始差异是"这一轮看到了什么问题"，
 * 工单是"这个问题持续了多久、谁在处理"。混成一个类型会导致写库时到处都在传
 * 首次发现时间、重复次数这些扫描器根本不关心的字段。
 *
 * @param targetKey 稳定标识，见 {@link FindingKind#projectionTargetKey}
 * @param detail    给人看的一句话（含具体数值与期望值）；不参与去重
 */
public record RawFinding(
        FindingKind kind,
        String targetKey,
        LocalDate stayDate,
        UUID roomId,
        UUID reservationId,
        String detail
) {

    public RawFinding {
        if (kind == null) {
            throw new IllegalArgumentException("差异必须指明类型");
        }
        if (targetKey == null || targetKey.isBlank()) {
            throw new IllegalArgumentException("差异必须带目标标识，否则无法去重");
        }
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("差异必须带说明，否则工单等于没写");
        }
    }

    public FindingSeverity severity() {
        return kind.severity();
    }
}
