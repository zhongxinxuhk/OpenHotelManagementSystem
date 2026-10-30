package cn.sec.hn.openbackupsaas.inventory.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 投影对账报告（S1-12 / E-03）。
 *
 * <p><b>核对三方，而不是两方：</b>
 * <ol>
 *   <li><b>权威</b>：{@code room_occupancy} 的当前有效状态（能不能卖的事实）；</li>
 *   <li><b>投影</b>：{@code room_inventory_day}（派生读模型，可能滞后）；</li>
 *   <li><b>流水</b>：{@code inventory_ledger}（写入当时的记账，净值应与占用状态一致）。</li>
 * </ol>
 * 只比"投影 vs 重算"会漏掉一类问题：重算逻辑与投影都按同一个错误口径算，两边一致但都是错的。
 * 流水是独立写下的，它与占用对不上时，说明记账链路出了问题（漏记、重复入账、对冲错单）。
 *
 * <p>{@link #truncated()} 为 true 表示差异超过上限被截断——报告仍然说明"有问题"，
 * 但不能用来判断问题规模。
 */
public record ReconcileReport(
        UUID hotelId,
        LocalDate from,
        LocalDate to,
        int expectedRows,
        int storedRows,
        int missingRows,
        int staleRows,
        int orphanRows,
        int ledgerMismatches,
        boolean truncated,
        List<Difference> differences
) {

    /** 差异上限：报告是给人看的，不是导出功能；超过就截断并标注。 */
    public static final int MAX_DIFFERENCES = 200;

    /**
     * 是否完全没有问题。
     *
     * <p>注意：这是**计算方法**，不是 record 组件，所以 JSON 里没有 {@code clean} 字段。
     * 客户端按四个计数（missing / stale / orphan / ledger）判断，比读一个布尔值更有用——
     * 出问题时能立刻知道是"没建"、"过期"、"多余"还是"记账不对"。
     */
    public boolean clean() {
        return missingRows == 0 && staleRows == 0 && orphanRows == 0 && ledgerMismatches == 0;
    }

    /**
     * @param kind MISSING 投影缺失 / STALE 标记不一致 / ORPHAN 多余行 / LEDGER 流水与占用不符
     */
    public record Difference(String kind, UUID roomId, LocalDate stayDate, String detail) {
    }
}
