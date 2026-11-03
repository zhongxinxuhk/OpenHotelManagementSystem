package cn.sec.hn.openbackupsaas.inventory.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 期望投影与已存投影的逐行比对（S1-12 引入，S2-05 抽成共用）。
 *
 * <h2>为什么抽出来</h2>
 * 即时核对（{@code /inventory/reconcile}）与对账扫描（S2-05）必须用<b>同一套</b>比对规则。
 * 两处各写一份，后果不是"多写几行代码"，而是两套口径慢慢漂移：某一处修了边界、
 * 另一处没修，于是同一个库在两份报告里给出不同结论，谁也不知道该信哪个。
 *
 * <p>这里只做纯计算：不做截断、不排序、不落库。截断是报告层的事（{@link ReconcileReport}），
 * 落库是工单层的事——把三件事混在一起，就会出现"扫描时被 200 条上限悄悄截断、
 * 剩下 3000 个问题永远没人知道"的事故。
 */
public final class ProjectionDiff {

    private ProjectionDiff() {
    }

    /**
     * 比对两个方向并给出差异。
     *
     * <p>方向必须分开统计：{@code MISSING} 是"应该有却没有"（日历会漏报占用、可能错卖），
     * {@code STALE} 是"有但标记不对"（同样会错卖，但至少行存在），
     * {@code ORPHAN} 是"不该有却存在"（多占房、可能误拒客人）。
     * 混成一个"不一致"计数，排障时就要重新分类一遍。
     */
    public static List<Difference> diff(List<ExpectedProjectionRow> expected,
                                        List<StoredProjectionRow> stored) {
        Map<String, StoredProjectionRow> remaining = new HashMap<>(stored.size() * 2);
        for (StoredProjectionRow row : stored) {
            remaining.put(key(row.roomId(), row.stayDate()), row);
        }

        List<Difference> differences = new ArrayList<>();
        for (ExpectedProjectionRow row : expected) {
            StoredProjectionRow storedRow = remaining.remove(key(row.roomId(), row.stayDate()));
            if (storedRow == null) {
                differences.add(new Difference(Kind.MISSING, row.roomId(), row.stayDate(),
                        "投影缺失：应为 reservation=" + row.reservationUnavailable()
                                + ", block=" + row.blockUnavailable()));
                continue;
            }
            if (storedRow.reservationUnavailable() != row.reservationUnavailable()
                    || storedRow.blockUnavailable() != row.blockUnavailable()) {
                differences.add(new Difference(Kind.STALE, row.roomId(), row.stayDate(),
                        "标记不一致：投影 reservation=" + storedRow.reservationUnavailable()
                                + ", block=" + storedRow.blockUnavailable()
                                + "；应为 reservation=" + row.reservationUnavailable()
                                + ", block=" + row.blockUnavailable()));
            }
        }
        // 剩下的都是"投影里有、按权威不该有"：房间被停用、或占用已释放但投影未跟上
        for (StoredProjectionRow row : remaining.values()) {
            differences.add(new Difference(Kind.ORPHAN, row.roomId(), row.stayDate(),
                    "多余投影行：该房该晚不应存在（房间可能已停用）"));
        }
        return differences;
    }

    private static String key(UUID roomId, LocalDate stayDate) {
        return roomId + "@" + stayDate;
    }

    public enum Kind {
        MISSING,
        STALE,
        ORPHAN
    }

    /** 一条差异：类型 + 位置 + 说明。位置是房间与住宿日，两者足以定位并重建。 */
    public record Difference(Kind kind, UUID roomId, LocalDate stayDate, String detail) {
    }
}
