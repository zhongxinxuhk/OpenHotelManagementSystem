package cn.sec.hn.openbackupsaas.reconcile.mapper;

import cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow;
import cn.sec.hn.openbackupsaas.reconcile.domain.FindingSeverity;
import cn.sec.hn.openbackupsaas.reconcile.domain.FindingView;
import cn.sec.hn.openbackupsaas.reconcile.domain.RawFinding;
import cn.sec.hn.openbackupsaas.reconcile.domain.ReconcileRunView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 对账批次、差异工单与扫描水位的数据访问（S2-05）。SQL 见
 * {@code resources/mapper/reconcile/ReconcileMapper.xml}。
 *
 * <p><b>为什么把这些查询放在对账自己的 Mapper 里</b>，而不是去改各业务域的 Mapper：
 * 对账需要"跨域读"（订单/占用/流水/投影/事件），把这些读语句塞进各域，
 * 会让域模块的接口为了对账的需要不断长大，而且很难说清某条语句到底是业务读还是审计读。
 * 这里全部只读、只用于发现差异，不参与任何写入路径。
 */
@Mapper
public interface ReconcileMapper {

    // ---------------- 批次 ----------------

    UUID insertRun(@Param("tenantId") UUID tenantId,
                   @Param("hotelId") UUID hotelId,
                   @Param("from") LocalDate from,
                   @Param("to") LocalDate to,
                   @Param("triggerSource") String triggerSource,
                   @Param("triggeredBy") UUID triggeredBy);

    int finishRun(@Param("runId") UUID runId,
                  @Param("newFindings") int newFindings,
                  @Param("repeatedFindings") int repeatedFindings,
                  @Param("repairedFindings") int repairedFindings,
                  @Param("supersededFindings") int supersededFindings,
                  @Param("openFindings") int openFindings);

    int failRun(@Param("runId") UUID runId, @Param("error") String error);

    /**
     * 回收陈旧的 RUNNING 批次。
     *
     * <p>批次表上有"同一门店只允许一个 RUNNING"的唯一索引（并发扫描没有意义，只会让
     * occurrence 计数不可解释）。若进程在扫描中途崩溃，那条 RUNNING 会永远留着，
     * 于是<b>后续所有对账都被挡在唯一索引上</b>——一个崩溃点就能让对账永久停摆。
     * 因此每轮开始前先把超过一定年龄的 RUNNING 标记为 FAILED。
     *
     * <p>年龄阈值要大于单轮最长耗时（窗口 62 天 + 全表比对），否则会误杀正在跑的批次。
     */
    int failStaleRuns(@Param("tenantId") UUID tenantId,
                      @Param("hotelId") UUID hotelId,
                      @Param("olderThanSeconds") long olderThanSeconds);

    ReconcileRunView findRun(@Param("tenantId") UUID tenantId,
                             @Param("hotelId") UUID hotelId,
                             @Param("runId") UUID runId);

    List<ReconcileRunView> findRecentRuns(@Param("tenantId") UUID tenantId,
                                          @Param("hotelId") UUID hotelId,
                                          @Param("limit") int limit);

    // ---------------- 工单 ----------------

    /**
     * 记录一条差异：同一（门店, 类型, 目标）已有 OPEN 工单时累加重复次数，不新建。
     *
     * <p>返回的 {@code occurrences == 1} 表示这是本轮新发现的——用它统计"新增"，
     * 而不是再查一次库（查一次就多一次竞态窗口）。
     *
     * <p>{@code severity} 由调用方从 {@link RawFinding#kind()} 派生后单独传入，而不是在
     * OGNL 里写 {@code #{f.severity}}：{@code severity()} 不是 record 组件，
     * MyBatis 的属性解析找不到它（会报 no getter）。传参虽然多一个形参，
     * 但它的值只有一个来源（枚举本身），不会漂移。
     */
    Upserted upsertFinding(@Param("tenantId") UUID tenantId,
                           @Param("hotelId") UUID hotelId,
                           @Param("f") RawFinding finding,
                           @Param("severity") FindingSeverity severity,
                           @Param("runId") UUID runId);

    /**
     * 把本轮没再看到、且落在本次扫描范围内的 OPEN 工单置为 SUPERSEDED。
     *
     * <p>范围判定必须看到 {@code stay_date}：如果无条件把所有没见的工单都退场，
     * 那么一次只扫最近 7 天的对账会把 30 天窗口里的老问题全部"自动关闭"，
     * 而它们其实还在。反过来，只看日期不看 {@code last_run_id} 就没法区分"本轮见过"。
     */
    int supersedeStale(@Param("tenantId") UUID tenantId,
                       @Param("hotelId") UUID hotelId,
                       @Param("runId") UUID runId,
                       @Param("from") LocalDate from,
                       @Param("to") LocalDate to);

    int markRepaired(@Param("tenantId") UUID tenantId,
                     @Param("hotelId") UUID hotelId,
                     @Param("findingId") UUID findingId);

    int markIgnored(@Param("tenantId") UUID tenantId,
                    @Param("hotelId") UUID hotelId,
                    @Param("findingId") UUID findingId,
                    @Param("operator") UUID operator,
                    @Param("reason") String reason);

    FindingView findFinding(@Param("tenantId") UUID tenantId,
                            @Param("hotelId") UUID hotelId,
                            @Param("findingId") UUID findingId);

    /** 未结工单，按分级与最近出现时间排序；{@code severity} 为空表示不过滤。 */
    List<FindingView> findOpenFindings(@Param("tenantId") UUID tenantId,
                                      @Param("hotelId") UUID hotelId,
                                      @Param("severity") FindingSeverity severity,
                                      @Param("limit") int limit);

    /** 已处理工单（修复/忽略/退场），供复盘。 */
    List<FindingView> findSettledFindings(@Param("tenantId") UUID tenantId,
                                          @Param("hotelId") UUID hotelId,
                                          @Param("limit") int limit);

    long countOpenBySeverity(@Param("tenantId") UUID tenantId,
                             @Param("hotelId") UUID hotelId,
                             @Param("severity") FindingSeverity severity);

    // ---------------- 水位 ----------------

    LocalDate findWatermark(@Param("tenantId") UUID tenantId, @Param("hotelId") UUID hotelId);

    /**
     * 推进水位，只进不退。
     *
     * <p>{@code GREATEST} 是刻意的：重扫历史区间（例如人工触发一次上个月的对账）
     * 不能把水位拉回去，否则定时任务下一轮会把已扫过的区间再扫一遍，白跑且会刷新
     * 工单的 {@code last_seen_at}，让"最近出现"失去意义。
     */
    int advanceWatermark(@Param("tenantId") UUID tenantId,
                         @Param("hotelId") UUID hotelId,
                         @Param("scannedThrough") LocalDate scannedThrough);

    // ---------------- 扫描查询 ----------------

    /** 订单状态与占用状态不一致（交易事实矛盾）。 */
    List<StatusMismatch> findStatusMismatches(@Param("w") InventoryWindow window);

    /** 有效状态订单没有任何有效占用（房间没被占住，可能超卖）。 */
    List<MissingOccupancy> findMissingOccupancies(@Param("w") InventoryWindow window);

    /**
     * 定时对账要扫的门店清单（跨租户）。
     *
     * <p>这是系统级任务，不属于任何单个租户，因此没有 tenant 参数——与到期承接扫描同构。
     * 只取 {@code ACTIVE} 门店：已停用门店不再有交易，扫它只会产生一堆无人处理的工单。
     * 返回顺序固定（按租户、门店 id），让多实例的扫描顺序可复现，便于比对日志。
     */
    List<HotelRef> findActiveHotels(@Param("limit") int limit);

    /** 未发布事件的积压情况；无积压时返回 {@code pending = 0}。 */
    BacklogStat outboxBacklog(@Param("tenantId") UUID tenantId,
                              @Param("hotelId") UUID hotelId,
                              @Param("olderThanSeconds") long olderThanSeconds);

    /** 消费端挂起事件的积压情况。 */
    BacklogStat inboxParked(@Param("tenantId") UUID tenantId,
                            @Param("hotelId") UUID hotelId,
                            @Param("olderThanSeconds") long olderThanSeconds);

    /**
     * 工单写入结果。
     *
     * @param occurrences 累计出现次数；为 1 说明本轮首次发现
     */
    record Upserted(UUID findingId, int occurrences) {
        public boolean isNew() {
            return occurrences == 1;
        }
    }

    /** 订单 ↔ 占用状态不一致。{@code expectedOccupancyStatus} 由状态映射表算出。 */
    record StatusMismatch(UUID occupancyId, UUID reservationId, UUID roomId, LocalDate stayDate,
                          String reservationStatus, String occupancyStatus,
                          String expectedOccupancyStatus) {
    }

    /** 有效订单缺少有效占用。 */
    record MissingOccupancy(UUID reservationId, LocalDate stayDate, String reservationStatus) {
    }

    /** 待扫描门店（跨租户系统任务用，不属于任何单个租户）。 */
    record HotelRef(UUID tenantId, UUID hotelId) {
    }

    /**
     * 积压统计。
     *
     * @param oldestSeconds 最老一条的年龄（秒）；无积压时为 0
     */
    record BacklogStat(int pending, long oldestSeconds) {
        public boolean breached() {
            return pending > 0;
        }
    }
}
