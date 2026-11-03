package cn.sec.hn.openbackupsaas.reconcile.web;

import cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow;
import io.swagger.v3.oas.annotations.Operation;
import cn.sec.hn.openbackupsaas.reconcile.domain.FindingSeverity;
import cn.sec.hn.openbackupsaas.reconcile.domain.FindingView;
import cn.sec.hn.openbackupsaas.reconcile.domain.ReconcileRunReport;
import cn.sec.hn.openbackupsaas.reconcile.domain.ReconcileRunView;
import cn.sec.hn.openbackupsaas.reconcile.service.ReconcileService;
import cn.sec.hn.openbackupsaas.shared.security.HotelAccess;
import cn.sec.hn.openbackupsaas.shared.security.TenantContext;
import cn.sec.hn.openbackupsaas.shared.security.TenantContextHolder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 对账接口（S2-05）。
 *
 * <h2>为什么和 {@code /inventory/reconcile} 并存</h2>
 * 那个是"现在对一下"的即时报告（无状态、跑完就忘，权限 {@code inventory:read}）；
 * 这里是"这件事还挂着没人管"的工单视图（有状态、有首次发现时间与重复次数）。
 * 两者共用同一套比对规则，所以结论不会互相矛盾——区别只在<b>有没有账</b>。
 *
 * <h2>权限</h2>
 * 读（概览/工单/批次）要 {@code reconcile:read}；触发扫描、修复、忽略要
 * {@code reconcile:operate}。修复虽然只动派生投影，但它会让看板上的问题消失，
 * 不能跟着"能看"一起给出去。
 *
 * <h2>操作者来源</h2>
 * 忽略差异记录的操作者取自服务端会话，不接受请求体自报——否则留痕里的"谁判断的"
 * 就成了一句自述，审计价值归零（与 S2-04 一致）。
 */
@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/reconcile")
public class ReconcileController {

    private final ReconcileService reconcileService;
    private final HotelAccess hotelAccess;

    public ReconcileController(ReconcileService reconcileService, HotelAccess hotelAccess) {
        this.reconcileService = reconcileService;
        this.hotelAccess = hotelAccess;
    }

    /**
     * 待处理概览。
     *
     * <p>同时返回水位与最近一批：{@code lastRun} 为 null 表示<b>还没跑过对账</b>，
     * 界面必须显示"未对账"而不是"0 条差异"——后者会被当成"一切正常"。
     */
    @GetMapping("/summary")
    @Operation(operationId = "getReconcileSummary")
    @PreAuthorize("hasAuthority('reconcile:read')")
    public SummaryView summary(@PathVariable UUID hotelId) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        ReconcileService.Summary summary = reconcileService.summary(tenantId, hotelId);
        return new SummaryView(summary.openDerived(), summary.openFact(), summary.openBacklog(),
                summary.openTotal(), summary.needsAttention(),
                reconcileService.watermark(tenantId, hotelId), summary.lastRun());
    }

    /**
     * 差异工单。
     *
     * @param status OPEN（默认，未结）或 SETTLED（已修复/已忽略/已退场，供复盘）
     */
    @GetMapping("/findings")
    @Operation(operationId = "listReconcileFindings")
    @PreAuthorize("hasAuthority('reconcile:read')")
    public List<FindingView> findings(@PathVariable UUID hotelId,
                                      @RequestParam(defaultValue = "OPEN") String status,
                                      @RequestParam(required = false) FindingSeverity severity,
                                      @RequestParam(defaultValue = "100") int limit) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        return "SETTLED".equalsIgnoreCase(status)
                ? reconcileService.settledFindings(tenantId, hotelId, limit)
                : reconcileService.openFindings(tenantId, hotelId, severity, limit);
    }

    /** 最近的批次，最新在前。 */
    @GetMapping("/runs")
    @Operation(operationId = "listReconcileRuns")
    @PreAuthorize("hasAuthority('reconcile:read')")
    public List<ReconcileRunView> runs(@PathVariable UUID hotelId,
                                       @RequestParam(defaultValue = "20") int limit) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        return reconcileService.recentRuns(tenantId, hotelId, limit);
    }

    /**
     * 人工触发一轮对账（窗口由调用方指定）。
     *
     * <p>允许扫历史：客诉、追责、补扫停机期间的空档都要看过去。窗口长度仍受
     * {@link InventoryWindow} 的 62 天上限约束，超限直接 400。
     *
     * @param autoRepair 是否顺带修复派生差异；默认 true（只可能修派生类）
     */
    @PostMapping("/run")
    @Operation(operationId = "runReconcile")
    @PreAuthorize("hasAuthority('reconcile:operate')")
    public ReconcileRunReport run(
            @PathVariable UUID hotelId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "true") boolean autoRepair) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        TenantContext context = TenantContextHolder.require();
        InventoryWindow window = new InventoryWindow(tenantId, hotelId, null, from, to);
        return reconcileService.run(tenantId, hotelId, window, "MANUAL", context.userId(), autoRepair);
    }

    /**
     * 定向修复一条派生差异。
     *
     * <p>事实类差异调用这里会得到 409 而不是"已修复"——系统不知道该改哪一边，
     * 假装修好只会把矛盾藏起来。
     */
    @PostMapping("/findings/{findingId}/repair")
    @Operation(operationId = "repairReconcileFinding")
    @PreAuthorize("hasAuthority('reconcile:operate')")
    public RepairResult repair(@PathVariable UUID hotelId, @PathVariable UUID findingId) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        boolean repaired = reconcileService.repairFinding(tenantId, hotelId, findingId);
        return new RepairResult(findingId, repaired, repaired ? "已按权威记录重建派生数据" : "差异仍存在");
    }

    /** 忽略一条差异（必须给原因）。 */
    @PostMapping("/findings/{findingId}/ignore")
    @Operation(operationId = "ignoreReconcileFinding")
    @PreAuthorize("hasAuthority('reconcile:operate')")
    public RepairResult ignore(@PathVariable UUID hotelId, @PathVariable UUID findingId,
                               @RequestParam String reason) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        TenantContext context = TenantContextHolder.require();
        reconcileService.ignoreFinding(tenantId, hotelId, findingId, context.userId(), reason);
        return new RepairResult(findingId, true, "已忽略：" + reason);
    }

    /**
     * 概览视图。
     *
     * @param scannedThrough 已扫过的最后一个住宿日；{@code null} 表示从未扫过
     * @param lastRun        最近一批；{@code null} 表示从未跑过
     */
    public record SummaryView(long openDerived, long openFact, long openBacklog, long openTotal,
                              boolean needsAttention, LocalDate scannedThrough,
                              ReconcileRunView lastRun) {
    }

    /** 修复/忽略的结果。 */
    public record RepairResult(UUID findingId, boolean applied, String detail) {
    }
}
