package cn.sec.hn.openbackupsaas.reconcile.service;

import cn.sec.hn.openbackupsaas.inventory.domain.ExpectedProjectionRow;
import cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow;
import cn.sec.hn.openbackupsaas.inventory.domain.ProjectionDiff;
import cn.sec.hn.openbackupsaas.inventory.domain.StoredProjectionRow;
import cn.sec.hn.openbackupsaas.inventory.mapper.InventoryMapper;
import cn.sec.hn.openbackupsaas.inventory.service.InventoryService;
import cn.sec.hn.openbackupsaas.reconcile.domain.FindingKind;
import cn.sec.hn.openbackupsaas.reconcile.domain.FindingSeverity;
import cn.sec.hn.openbackupsaas.reconcile.domain.FindingView;
import cn.sec.hn.openbackupsaas.reconcile.domain.RawFinding;
import cn.sec.hn.openbackupsaas.reconcile.domain.ReconcileRunReport;
import cn.sec.hn.openbackupsaas.reconcile.domain.ReconcileRunView;
import cn.sec.hn.openbackupsaas.reconcile.mapper.ReconcileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 内部对账与派生修复（S2-05）。
 *
 * <h2>它解决的问题</h2>
 * S1-12 的即时核对是"跑一下看一眼"，跑完就忘。没人知道同一个问题已经连续存在三周。
 * 这里把差异变成<b>有身份的工单</b>：首次发现时间、重复次数、状态，重复扫描只累加不新建。
 *
 * <h2>三条不能越过的线</h2>
 * <ol>
 *   <li><b>绝不改写交易事实。</b>订单、占用、流水一律不碰。修复动作在白名单里只有一种：
 *       按权威记录重建派生投影（{@code REBUILD_PROJECTION}）。发现"订单与占用状态矛盾"时，
 *       它的价值是<b>告诉人去看</b>，而不是自动"顺手改一致"——那样会把"哪边错了"的证据抹掉。</li>
 *   <li><b>绝不补造数据。</b>没有订单就是没有订单；缺投影可以重建，缺订单只能人去补办。</li>
 *   <li><b>修复结果要复核。</b>重建后重新比对一次，只把确实消失的差异标为已修复。
 *       直接假定"重建成功 = 全部修好"会在报告里写下假账——而假账比没有报告更糟。</li>
 * </ol>
 *
 * <h2>为什么整轮不放在一个事务里</h2>
 * 扫描一轮可能持续数十秒（窗口 62 天 + 全表比对）。把它包在一个事务里，等于在事务期间
 * 持有一堆读锁、并把批次表的那行一直标成 RUNNING；中途崩溃还会留下永远 RUNNING 的批次，
 * 把后续所有对账挡在唯一索引上。所以：每条语句独立提交，崩溃点由
 * {@link ReconcileMapper#failStaleRuns} 回收，重复扫描是幂等的（工单靠唯一索引去重）。
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    /** 陈旧批次判定：超过这个时长仍是 RUNNING 视为崩溃遗留。要大于单轮最长耗时。 */
    private static final long STALE_RUN_SECONDS = 600;

    private final ReconcileMapper reconcileMapper;
    private final InventoryMapper inventoryMapper;
    private final InventoryService inventoryService;
    private final Environment environment;

    public ReconcileService(ReconcileMapper reconcileMapper,
                            InventoryMapper inventoryMapper,
                            InventoryService inventoryService,
                            Environment environment) {
        this.reconcileMapper = reconcileMapper;
        this.inventoryMapper = inventoryMapper;
        this.inventoryService = inventoryService;
        this.environment = environment;
    }

    // ==================================================================
    // 对外：跑一轮
    // ==================================================================

    /**
     * 跑一轮对账。
     *
     * @param autoRepair 是否自动修复派生类差异；事实类与积压类不受此开关影响（它们没有修复路径）
     */
    public ReconcileRunReport run(UUID tenantId, UUID hotelId, InventoryWindow window,
                                  String triggerSource, UUID triggeredBy, boolean autoRepair) {
        Objects.requireNonNull(tenantId, "对账必须限定租户");
        Objects.requireNonNull(hotelId, "对账必须限定门店");

        // 先回收崩溃遗留的 RUNNING，否则唯一索引会把这一轮直接挡掉
        int reclaimed = reconcileMapper.failStaleRuns(tenantId, hotelId, STALE_RUN_SECONDS);
        if (reclaimed > 0) {
            log.warn("回收 {} 个超时未结束的对账批次（进程可能崩溃）", reclaimed);
        }

        UUID runId = reconcileMapper.insertRun(tenantId, hotelId, window.from(), window.to(),
                triggerSource, triggeredBy);

        try {
            List<RawFinding> findings = scan(tenantId, hotelId, window);

            int newly = 0;
            int repeated = 0;
            List<Repairable> repairables = new ArrayList<>();
            for (RawFinding finding : findings) {
                ReconcileMapper.Upserted upserted = reconcileMapper.upsertFinding(
                        tenantId, hotelId, finding, finding.severity(), runId);
                if (upserted.isNew()) {
                    newly++;
                } else {
                    repeated++;
                }
                if (finding.kind().allowsAutomaticRepair()) {
                    repairables.add(new Repairable(upserted.findingId(), finding.targetKey()));
                }
            }

            // 退场必须在"本轮全部记录完"之后：否则本轮刚记下的工单会被自己判成"没再见到"
            int superseded = reconcileMapper.supersedeStale(tenantId, hotelId, runId,
                    window.from(), window.to());

            int repaired = 0;
            if (autoRepair && !repairables.isEmpty()) {
                repaired = repairDerived(tenantId, hotelId, window, repairables);
            }

            int openDerived = (int) reconcileMapper.countOpenBySeverity(tenantId, hotelId, FindingSeverity.DERIVED);
            int openFact = (int) reconcileMapper.countOpenBySeverity(tenantId, hotelId, FindingSeverity.FACT);
            int openBacklog = (int) reconcileMapper.countOpenBySeverity(tenantId, hotelId, FindingSeverity.BACKLOG);
            int open = openDerived + openFact + openBacklog;

            reconcileMapper.finishRun(runId, newly, repeated, repaired, superseded, open);

            // 水位只在"扫到今天及以后"时才推进；人工重扫历史区间不应把水位拉回去
            advanceWatermark(tenantId, hotelId, window.to());

            return new ReconcileRunReport(runId, hotelId, window.from(), window.to(), triggerSource,
                    newly, repeated, repaired, superseded, open, openDerived, openFact, openBacklog);
        } catch (RuntimeException ex) {
            // 批次失败要落状态，不能只写日志：否则看板上"最近一次对账"还是上一次的成功批次
            reconcileMapper.failRun(runId, truncate(String.valueOf(ex)));
            throw ex;
        }
    }

    // ==================================================================
    // 扫描
    // ==================================================================

    /**
     * 收集这一轮的差异。
     *
     * <p>投影与流水复用 S1-12 的查询（同一段期望投影 SQL、同一个 {@link ProjectionDiff}），
     * 这样"即时核对"与"工单对账"不可能给出互相矛盾的结论。
     */
    private List<RawFinding> scan(UUID tenantId, UUID hotelId, InventoryWindow window) {
        List<RawFinding> findings = new ArrayList<>();

        // 1. 投影三类：缺失 / 标记不一致 / 多余
        List<ExpectedProjectionRow> expected = inventoryMapper.computeExpectedProjection(window);
        List<StoredProjectionRow> stored = inventoryMapper.findStoredProjection(window);
        for (ProjectionDiff.Difference diff : ProjectionDiff.diff(expected, stored)) {
            FindingKind kind = switch (diff.kind()) {
                case MISSING -> FindingKind.MISSING_PROJECTION;
                case STALE -> FindingKind.STALE_PROJECTION;
                case ORPHAN -> FindingKind.ORPHAN_PROJECTION;
            };
            findings.add(new RawFinding(kind,
                    FindingKind.projectionTargetKey(diff.roomId(), diff.stayDate()),
                    diff.stayDate(), diff.roomId(), null, diff.detail()));
        }

        // 2. 流水与占用不符（记账链路问题，事实类）
        for (InventoryMapper.LedgerMismatch mismatch : inventoryMapper.findLedgerMismatches(window)) {
            findings.add(new RawFinding(FindingKind.LEDGER_MISMATCH,
                    FindingKind.projectionTargetKey(mismatch.roomId(), mismatch.stayDate()),
                    mismatch.stayDate(), mismatch.roomId(), null,
                    "流水净值=" + mismatch.ledgerDelta() + "，当前占用=" + mismatch.occupied()));
        }

        // 3. 订单状态与占用状态矛盾
        for (ReconcileMapper.StatusMismatch mismatch : reconcileMapper.findStatusMismatches(window)) {
            findings.add(new RawFinding(FindingKind.STATUS_MISMATCH,
                    FindingKind.occupancyTargetKey(mismatch.occupancyId()),
                    mismatch.stayDate(), mismatch.roomId(), mismatch.reservationId(),
                    "订单 " + mismatch.reservationStatus() + " 应对应占用 "
                            + mismatch.expectedOccupancyStatus() + "，实际占用 "
                            + mismatch.occupancyStatus()));
        }

        // 4. 有效订单没有任何有效占用：可能被卖第二次
        for (ReconcileMapper.MissingOccupancy missing : reconcileMapper.findMissingOccupancies(window)) {
            findings.add(new RawFinding(FindingKind.MISSING_OCCUPANCY,
                    FindingKind.reservationTargetKey(missing.reservationId()),
                    missing.stayDate(), null, missing.reservationId(),
                    "订单状态 " + missing.reservationStatus() + " 但没有任何有效占用（房间未被占住）"));
        }

        // 5. 积压类：数据本身没错，是链路没跟上
        long outboxAge = durationSeconds("pms.reconcile.backlog.outbox-age", Duration.ofMinutes(15));
        ReconcileMapper.BacklogStat outbox = reconcileMapper.outboxBacklog(tenantId, hotelId, outboxAge);
        if (outbox.breached()) {
            findings.add(new RawFinding(FindingKind.OUTBOX_BACKLOG,
                    FindingKind.hotelScopeTargetKey("outbox"), null, null, null,
                    "未发布事件 " + outbox.pending() + " 条，最老一条已 "
                            + outbox.oldestSeconds() + " 秒未发出去"));
        }

        long inboxAge = durationSeconds("pms.reconcile.backlog.inbox-age", Duration.ofMinutes(15));
        ReconcileMapper.BacklogStat inbox = reconcileMapper.inboxParked(tenantId, hotelId, inboxAge);
        if (inbox.breached()) {
            findings.add(new RawFinding(FindingKind.INBOX_PARKED,
                    FindingKind.hotelScopeTargetKey("inbox"), null, null, null,
                    "挂起事件 " + inbox.pending() + " 条，最老一条已 "
                            + inbox.oldestSeconds() + " 秒未处理"));
        }

        return findings;
    }

    // ==================================================================
    // 修复
    // ==================================================================

    /**
     * 修复派生差异：按权威记录重建窗口投影，然后<b>重新比对</b>，只把确实消失的标为已修复。
     *
     * <p>不做"重建完就全部标修复"：重建是整窗口操作，如果某条差异的原因不是投影滞后
     * （例如房间停用后又启用、比对口径本身有 bug），它会依然存在。全标修复等于对运维说谎，
     * 而且下一次扫描又会新建工单，看起来像"修了又坏"，反而更难查。
     */
    private int repairDerived(UUID tenantId, UUID hotelId, InventoryWindow window,
                              List<Repairable> repairables) {
        inventoryService.rebuild(window);

        // 重建后重新比对，得到仍然存在的差异
        List<ExpectedProjectionRow> expected = inventoryMapper.computeExpectedProjection(window);
        List<StoredProjectionRow> stored = inventoryMapper.findStoredProjection(window);
        Set<String> stillBroken = new HashSet<>();
        for (ProjectionDiff.Difference diff : ProjectionDiff.diff(expected, stored)) {
            stillBroken.add(FindingKind.projectionTargetKey(diff.roomId(), diff.stayDate()));
        }

        int repaired = 0;
        for (Repairable repairable : repairables) {
            if (stillBroken.contains(repairable.targetKey())) {
                log.warn("重建后差异仍然存在，保留工单：{}", repairable.targetKey());
                continue;
            }
            repaired += reconcileMapper.markRepaired(tenantId, hotelId, repairable.findingId());
        }
        log.info("派生修复：候选 {} 条，实际修复 {} 条", repairables.size(), repaired);
        return repaired;
    }

    /**
     * 人工定向修复一条派生差异。
     *
     * <p>只接受派生类：事实类差异即使有人点"修复"，系统也不知道该改哪边（订单错了还是占用错了？），
     * 因此抛 409 而不是偷偷把工单标成"已修复"把问题藏起来。
     *
     * <p>修复本身仍走"重建 + 复核"：重建后差异若依然存在，说明它不是投影滞后引起的，
     * 这时返回 409 并保留工单——比报告"已修复"然后下一轮又冒出来更诚实。
     *
     * @return true 表示差异确实消失
     * @throws NotFoundException 工单不存在或不属于该门店（不区分"他店有"与"根本没有"）
     * @throws ConflictException 工单不是 OPEN、类型不可自动修复、或重建后差异仍在
     */
    public boolean repairFinding(UUID tenantId, UUID hotelId, UUID findingId) {
        FindingView finding = reconcileMapper.findFinding(tenantId, hotelId, findingId);
        if (finding == null) {
            throw new NotFoundException("对账差异不存在");
        }
        if (!finding.open()) {
            throw new ConflictException("差异已处于 " + finding.status() + " 状态，无需修复");
        }
        if (!finding.kind().allowsAutomaticRepair()) {
            throw new ConflictException("差异类型 " + finding.kind() + " 属 " + finding.severity()
                    + "，不允许自动修复；请在业务系统里处置后由对账自动退场");
        }
        LocalDate from = finding.stayDate() == null ? LocalDate.now() : finding.stayDate();
        InventoryWindow window = new InventoryWindow(tenantId, hotelId, null, from, from.plusDays(1));
        int repaired = repairDerived(tenantId, hotelId, window,
                List.of(new Repairable(findingId, finding.targetKey())));
        if (repaired == 0) {
            throw new ConflictException("重建后差异仍然存在，说明不是投影滞后引起的；工单保留待人工判断");
        }
        return true;
    }

    /** 忽略一条差异（必须给原因）。返回是否生效。 */
    public boolean ignoreFinding(UUID tenantId, UUID hotelId, UUID findingId,
                                 UUID operator, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("忽略差异必须给出原因");
        }
        if (reconcileMapper.markIgnored(tenantId, hotelId, findingId, operator, reason) == 0) {
            // 0 行有两种可能：不存在，或已经不是 OPEN。统一按"不存在"处理以不泄露他店对象存在性，
            // 已结案的工单重复忽略也只是一种无害的重复请求。
            throw new NotFoundException("对账差异不存在或已处理");
        }
        return true;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public List<FindingView> openFindings(UUID tenantId, UUID hotelId,
                                          FindingSeverity severity, int limit) {
        return reconcileMapper.findOpenFindings(tenantId, hotelId, severity, clamp(limit));
    }

    public List<FindingView> settledFindings(UUID tenantId, UUID hotelId, int limit) {
        return reconcileMapper.findSettledFindings(tenantId, hotelId, clamp(limit));
    }

    public List<ReconcileRunView> recentRuns(UUID tenantId, UUID hotelId, int limit) {
        return reconcileMapper.findRecentRuns(tenantId, hotelId, clamp(limit));
    }

    public FindingView findFinding(UUID tenantId, UUID hotelId, UUID findingId) {
        return reconcileMapper.findFinding(tenantId, hotelId, findingId);
    }

    /** 待处理概览：按分级计数，给看板与值班用。 */
    public Summary summary(UUID tenantId, UUID hotelId) {
        long derived = reconcileMapper.countOpenBySeverity(tenantId, hotelId, FindingSeverity.DERIVED);
        long fact = reconcileMapper.countOpenBySeverity(tenantId, hotelId, FindingSeverity.FACT);
        long backlog = reconcileMapper.countOpenBySeverity(tenantId, hotelId, FindingSeverity.BACKLOG);
        List<ReconcileRunView> runs = reconcileMapper.findRecentRuns(tenantId, hotelId, 1);
        return new Summary(derived, fact, backlog, derived + fact + backlog,
                runs.isEmpty() ? null : runs.get(0));
    }

    /** 扫描水位：已扫过的最后一个住宿日；从未扫过时为 {@code null}。 */
    public LocalDate watermark(UUID tenantId, UUID hotelId) {
        return reconcileMapper.findWatermark(tenantId, hotelId);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 推进水位，只进不退。
     *
     * <p>{@code scannedThrough} 传的是窗口的右边界（开区间端点），水位记成"最后一个已扫过的住宿日"，
     * 所以减一天。写成 {@code minusDays(1)} 而不是让调用方各减一次，是为了让"水位是闭区间"
     * 这个约定只在一个地方出现。
     */
    private void advanceWatermark(UUID tenantId, UUID hotelId, LocalDate windowToExclusive) {
        reconcileMapper.advanceWatermark(tenantId, hotelId, windowToExclusive.minusDays(1));
    }

    private long durationSeconds(String key, Duration fallback) {
        Duration value = environment.getProperty(key, Duration.class);
        return (value == null ? fallback : value).toSeconds();
    }

    private static int clamp(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, 500);
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    /** 一条可修复差异的引用。 */
    private record Repairable(UUID findingId, String targetKey) {
    }

    /**
     * 待处理概览。
     *
     * @param lastRun 最近一批；从未跑过时为 {@code null}（界面必须显示"还没有跑过对账"，
     *                而不是显示 0 条未处理——那会被误读成"一切正常"）
     */
    public record Summary(long openDerived, long openFact, long openBacklog, long openTotal,
                          ReconcileRunView lastRun) {

        public boolean needsAttention() {
            return openFact > 0 || openBacklog > 0;
        }
    }
}
