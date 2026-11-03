package cn.sec.hn.openbackupsaas.inventory.service;

import cn.sec.hn.openbackupsaas.inventory.domain.BookableRoomType;
import cn.sec.hn.openbackupsaas.inventory.domain.ExpectedProjectionRow;
import cn.sec.hn.openbackupsaas.inventory.domain.InventoryDay;
import cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow;
import cn.sec.hn.openbackupsaas.inventory.domain.ProjectionDiff;
import cn.sec.hn.openbackupsaas.inventory.domain.ReconcileReport;
import cn.sec.hn.openbackupsaas.inventory.domain.StoredProjectionRow;
import cn.sec.hn.openbackupsaas.inventory.mapper.InventoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 房态与可售（S1-12）：查询永远读权威，投影只是可重建的读模型。
 *
 * <p><b>为什么查询不读投影：</b>投影会滞后（重建是运维动作，不是每次写占用都同步）。
 * 用滞后的投影回答"能不能卖"，就会出现"页面说可售、下单被排斥约束拒绝"的错位，
 * 而这种错位在客人眼里就是"系统出错了"。权威记录（{@code room_occupancy} + 排斥约束）
 * 永远自洽，从它算出来的答案也永远与写入一致——代价只是每次多算一点，
 * 而窗口已被限制在 62 天内，这点代价远小于解释一次错卖的代价。
 *
 * <p><b>投影存在的意义</b>是给日历、报表这类"批量读同一天段"的场景省算力，
 * 以及给对账一个可比对的对象。它可以被随时清空重建（{@link #rebuild}）。
 */
@Service
public class InventoryService {

    private final InventoryMapper inventoryMapper;

    public InventoryService(InventoryMapper inventoryMapper) {
        this.inventoryMapper = inventoryMapper;
    }

    /** 按房型 × 每晚的余量，直接从权威记录算。 */
    @Transactional(readOnly = true)
    public List<InventoryDay> dailyAvailability(InventoryWindow window) {
        return inventoryMapper.findDailyAvailability(window);
    }

    /**
     * 整段可售的房间数（按房型）。
     *
     * <p>这是"能不能承诺这一段住宿"的唯一依据：返回 0 或缺失的房型，
     * 界面必须显示不可售，不能拿每日余量最小值去猜。
     */
    @Transactional(readOnly = true)
    public List<BookableRoomType> bookableRooms(InventoryWindow window) {
        return inventoryMapper.findBookableRooms(window);
    }

    /**
     * 重建窗口内的投影。
     *
     * <p>顺序是"先删后写"，两者在同一事务里：对读事务来说要么看到旧投影、要么看到新投影，
     * 不存在"清空后还没写"的空窗（那会瞬间显示全店可售）。
     *
     * <p>为什么允许删掉再重建而不是增量修补：投影本身是可丢弃的派生数据（ADR-001），
     * 增量修补需要判断"哪些行该变"，判断逻辑一旦出错就再也回不到一致状态；
     * 整段重算的代价是确定的，结果也确定。
     */
    @Transactional
    public RebuildResult rebuild(InventoryWindow window) {
        int deleted = inventoryMapper.deleteProjection(window);
        int written = inventoryMapper.rebuildProjection(window);
        return new RebuildResult(window.from(), window.to(), deleted, written);
    }

    /**
     * 三方对账（即时核对）：权威重算 vs 投影 vs 流水。
     *
     * <p>这是"现在对一下"的一次性报告，跑完就忘。需要"这件事还挂着没人管"的持续性视图，
     * 用 S2-05 的对账工单（{@code /reconcile/findings}）——两者共用同一套比对规则
     * （{@link ProjectionDiff} 与同一段期望投影 SQL），因此不会出现两份报告结论不一致。
     *
     * <p>报告只读到差异上限（{@link ReconcileReport#MAX_DIFFERENCES}）为止并标注截断，
     * 因为它是给人读的问题线索，不是导出功能；但计数是完整的——"有几处不一致"必须可信。
     */
    @Transactional(readOnly = true)
    public ReconcileReport reconcile(InventoryWindow window) {
        List<ExpectedProjectionRow> expected = inventoryMapper.computeExpectedProjection(window);
        List<StoredProjectionRow> stored = inventoryMapper.findStoredProjection(window);
        List<ProjectionDiff.Difference> projectionDiffs = ProjectionDiff.diff(expected, stored);

        int missing = 0;
        int stale = 0;
        int orphan = 0;
        List<ReconcileReport.Difference> differences = new ArrayList<>();
        for (ProjectionDiff.Difference diff : projectionDiffs) {
            switch (diff.kind()) {
                case MISSING -> missing++;
                case STALE -> stale++;
                case ORPHAN -> orphan++;
            }
            differences.add(new ReconcileReport.Difference(diff.kind().name(),
                    diff.roomId(), diff.stayDate(), diff.detail()));
        }

        List<InventoryMapper.LedgerMismatch> ledgerMismatches = inventoryMapper.findLedgerMismatches(window);
        for (InventoryMapper.LedgerMismatch mismatch : ledgerMismatches) {
            differences.add(new ReconcileReport.Difference("LEDGER", mismatch.roomId(), mismatch.stayDate(),
                    "流水与占用不符：流水净值=" + mismatch.ledgerDelta()
                            + "，当前占用=" + mismatch.occupied()));
        }

        boolean truncated = differences.size() > ReconcileReport.MAX_DIFFERENCES;
        if (truncated) {
            // 计数在上面已经全部统计完，这里只裁明细：先全收再裁剪，避免"计到一半就停"
            differences = new ArrayList<>(differences.subList(0, ReconcileReport.MAX_DIFFERENCES));
        }

        return new ReconcileReport(window.hotelId(), window.from(), window.to(),
                expected.size(), stored.size(), missing, stale, orphan, ledgerMismatches.size(),
                truncated, List.copyOf(differences));
    }

    /** 重建结果：删除与写入的行数用于运维确认"这段确实重算过"。 */
    public record RebuildResult(LocalDate from, LocalDate to, int deletedRows, int writtenRows) {
    }
}
