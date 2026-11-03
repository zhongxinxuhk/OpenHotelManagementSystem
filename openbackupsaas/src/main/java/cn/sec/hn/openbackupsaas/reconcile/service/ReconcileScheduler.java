package cn.sec.hn.openbackupsaas.reconcile.service;

import cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow;
import cn.sec.hn.openbackupsaas.reconcile.domain.ReconcileRunReport;
import cn.sec.hn.openbackupsaas.reconcile.mapper.ReconcileMapper;
import cn.sec.hn.openbackupsaas.shared.runtime.WorkerRoleCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 定时对账（S2-05）。
 *
 * <p>窗口的确定方式是<b>从水位往后扫固定天数</b>，不是"扫今天到未来 N 天"：
 * 系统停机三天后，如果只扫"今天往后"，那停机的三天永远不会被对账覆盖。
 * 水位就是为这件事存在的。
 *
 * <p>只在 Worker 角色下装配：对账是重读操作（窗口内房间 × 每晚），放在接口进程里
 * 会和客人的查询抢数据库；而且接口进程重启会把扫到一半的批次打断。
 */
@Component
@Conditional(WorkerRoleCondition.class)
@ConditionalOnProperty(name = "pms.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class ReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconcileScheduler.class);

    /** 单轮最多推进的天数，防止水位落后一年时一次扫出一个巨大的窗口。 */
    private static final int MAX_WINDOW_DAYS = InventoryWindow.MAX_WINDOW_DAYS;

    private final ReconcileService reconcileService;
    private final ReconcileMapper reconcileMapper;
    private final Environment environment;

    @Value("${pms.reconcile.hotel-batch-size:50}")
    private int hotelBatchSize;

    public ReconcileScheduler(ReconcileService reconcileService,
                              ReconcileMapper reconcileMapper,
                              Environment environment) {
        this.reconcileService = reconcileService;
        this.reconcileMapper = reconcileMapper;
        this.environment = environment;
    }

    @Scheduled(fixedDelayString = "${pms.reconcile.poll-interval:PT1H}",
            initialDelayString = "${pms.reconcile.initial-delay:PT5M}")
    public void poll() {
        try {
            runOnce();
        } catch (RuntimeException ex) {
            // 单个门店失败不应拖住其它门店：runOnce 内部已逐店 try/catch，这里只兜调度本身
            log.error("对账轮询失败（下一轮会重试）", ex);
        }
    }

    /**
     * 扫一轮全部门店。
     *
     * @return 实际执行的批次结果，供运行态冒烟与测试观察
     */
    public List<ReconcileRunReport> runOnce() {
        boolean autoRepair = boolProperty("pms.reconcile.auto-repair", true);
        int windowDays = intProperty("pms.reconcile.window-days", 14);

        List<ReconcileMapper.HotelRef> hotels = reconcileMapper.findActiveHotels(hotelBatchSize);
        return hotels.stream()
                .map(hotel -> runForHotel(hotel, windowDays, autoRepair))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private ReconcileRunReport runForHotel(ReconcileMapper.HotelRef hotel, int windowDays,
                                           boolean autoRepair) {
        LocalDate today = LocalDate.now();
        LocalDate watermark = reconcileService.watermark(hotel.tenantId(), hotel.hotelId());
        LocalDate from = watermark == null ? today : watermark.plusDays(1);
        if (from.isBefore(today)) {
            // 水位落后太多时把起点抬到今天：历史缺口应由人工定向补扫（POST /reconcile/run），
            // 自动任务一次只做有限的工作量，否则一台新上线门店第一次对账会扫穿整个历史。
            from = today;
        }
        LocalDate to = from.plusDays(Math.min(windowDays, MAX_WINDOW_DAYS));
        try {
            ReconcileRunReport report = reconcileService.run(hotel.tenantId(), hotel.hotelId(),
                    new InventoryWindow(hotel.tenantId(), hotel.hotelId(), null, from, to),
                    "SCHEDULED", null, autoRepair);
            if (report.needsAttention() || report.newFindings() > 0) {
                log.warn("对账门店 {} 窗口 {}~{}：新增 {}，重复 {}，修复 {}，退场 {}，待处理 事实类 {} / 积压类 {}",
                        hotel.hotelId(), report.windowFrom(), report.windowTo(),
                        report.newFindings(), report.repeatedFindings(), report.repairedFindings(),
                        report.supersededFindings(), report.openFact(), report.openBacklog());
            } else {
                log.info("对账门店 {} 窗口 {}~{}：无差异", hotel.hotelId(), report.windowFrom(), report.windowTo());
            }
            return report;
        } catch (RuntimeException ex) {
            // 一个门店失败不影响其它门店；批次表里已经落了 FAILED（ReconcileService 负责）
            log.error("对账门店 {} 失败", hotel.hotelId(), ex);
            return null;
        }
    }

    private boolean boolProperty(String key, boolean fallback) {
        Boolean value = environment.getProperty(key, Boolean.class);
        return value == null ? fallback : value;
    }

    private int intProperty(String key, int fallback) {
        Integer value = environment.getProperty(key, Integer.class);
        return value == null ? fallback : value;
    }

    /** 供配置自检与文档引用：默认窗口天数。 */
    public Duration pollInterval() {
        Duration value = environment.getProperty("pms.reconcile.poll-interval", Duration.class);
        return value == null ? Duration.ofHours(1) : value;
    }
}
