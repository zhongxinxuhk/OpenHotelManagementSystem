package cn.sec.hn.openbackupsaas.reconcile.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 对账能发现的差异类型（S2-05），每类自带分级与修复动作。
 *
 * <h2>为什么分级与动作写在同一处</h2>
 * 如果把"这是哪类差异"和"能不能自动修"分在两个地方写（比如一个 Map 配策略、一个 switch 判断），
 * 迟早会出现某一类被漏配然后落到默认分支被自动修复。这里把两者绑进枚举常量，
 * 并在静态块里断言<b>非派生类不得声明修复动作</b>：加错会在类加载时直接失败，
 * 而不是在某个深夜的对账任务里悄悄改掉交易事实。
 *
 * <h2>为什么每个类型都要有稳定的 {@code targetKey}</h2>
 * 工单靠（门店, 类型, 目标）去重。同一个逻辑问题在两次扫描里必须算出同一个目标标识，
 * 否则每轮都会新建一张工单，看板被刷屏，真正的老问题反而被淹没。
 */
public enum FindingKind {

    MISSING_PROJECTION(FindingSeverity.DERIVED, RepairAction.REBUILD_PROJECTION,
            "投影缺失：该房该晚应有标记但投影里没有"),
    STALE_PROJECTION(FindingSeverity.DERIVED, RepairAction.REBUILD_PROJECTION,
            "投影标记与权威记录不一致"),
    ORPHAN_PROJECTION(FindingSeverity.DERIVED, RepairAction.REBUILD_PROJECTION,
            "投影多余：该房该晚不该有行"),

    LEDGER_MISMATCH(FindingSeverity.FACT, RepairAction.NONE,
            "库存流水净值与占用状态不符（记账链路问题）"),
    STATUS_MISMATCH(FindingSeverity.FACT, RepairAction.NONE,
            "订单状态与占用状态不一致（交易事实矛盾）"),
    MISSING_OCCUPANCY(FindingSeverity.FACT, RepairAction.NONE,
            "订单处于有效状态却没有有效占用（房间没被占住，可能超卖）"),

    OUTBOX_BACKLOG(FindingSeverity.BACKLOG, RepairAction.NONE,
            "未发布事件超过年龄阈值（投递链路积压）"),
    INBOX_PARKED(FindingSeverity.BACKLOG, RepairAction.NONE,
            "消费端存在超龄挂起事件（版本缺口未补齐或被丢弃）");

    private final FindingSeverity severity;
    private final RepairAction repairAction;
    private final String description;

    FindingKind(FindingSeverity severity, RepairAction repairAction, String description) {
        this.severity = severity;
        this.repairAction = repairAction;
        this.description = description;
    }

    static {
        // 铁律的机器保证：非派生类若声明了自动修复动作，直接让应用起不来。
        // 这条断言比任何注释都硬——注释会被忽略，类加载失败不会。
        for (FindingKind kind : values()) {
            if (!kind.severity.allowsAutomaticRepair() && kind.repairAction.isAutomatic()) {
                throw new IllegalStateException(
                        "对账差异类型 " + kind.name() + " 属于 " + kind.severity
                                + "，不允许声明自动修复动作 " + kind.repairAction
                                + "：事实类与积压类差异只能人工处置或靠重试消化");
            }
        }
    }

    public FindingSeverity severity() {
        return severity;
    }

    public RepairAction repairAction() {
        return repairAction;
    }

    public String description() {
        return description;
    }

    public boolean allowsAutomaticRepair() {
        return severity.allowsAutomaticRepair() && repairAction.isAutomatic();
    }

    /**
     * 投影类差异的目标标识：房间 + 住宿日。
     *
     * <p>用固定格式而不是 UUID 拼接的自由文本：日期只是日期、房间只是 UUID，
     * 两段之间不允许出现分隔符歧义。格式变更会破坏去重（老工单匹配不上新工单），
     * 因此改动时必须同时写一次性迁移。
     */
    public static String projectionTargetKey(java.util.UUID roomId, java.time.LocalDate stayDate) {
        Objects.requireNonNull(roomId, "房间不能为空");
        Objects.requireNonNull(stayDate, "住宿日不能为空");
        return "room:" + roomId + ":" + stayDate;
    }

    /** 订单级差异的目标标识。 */
    public static String reservationTargetKey(java.util.UUID reservationId) {
        Objects.requireNonNull(reservationId, "订单不能为空");
        return "reservation:" + reservationId;
    }

    /** 占用级差异的目标标识（占用的 id 比订单更精确：同一订单可能多行）。 */
    public static String occupancyTargetKey(java.util.UUID occupancyId) {
        Objects.requireNonNull(occupancyId, "占用不能为空");
        return "occupancy:" + occupancyId;
    }

    /** 门店级积压的目标标识（积压是"门店范围内有多少条"，不针对单个对象）。 */
    public static String hotelScopeTargetKey(String scope) {
        return "hotel:" + scope;
    }

    /** 全部派生类差异（自动修复的作用域）；顺序跟随枚举声明，便于日志阅读。 */
    public static List<FindingKind> derivedKinds() {
        return Arrays.stream(values())
                .filter(FindingKind::allowsAutomaticRepair)
                .toList();
    }
}
