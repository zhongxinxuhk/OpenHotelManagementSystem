package cn.sec.hn.openbackupsaas.reservation.domain;

/**
 * 占用状态。**只有落在有效集合里的状态才参与互斥**（与 V4 的排斥谓词一致）：
 * 终态（RELEASED / EXPIRED / COMPLETED / SUPERSEDED / UNBLOCKED）退出谓词，房间随之可再售，
 * 不需要删除行——历史占用必须留着对账。
 */
public enum OccupancyStatus {
    HELD,
    CONFIRMED,
    CHECKED_IN,
    RELEASED,
    EXPIRED,
    COMPLETED,
    SUPERSEDED,
    BLOCKED,
    UNBLOCKED;

    private static final java.util.Set<OccupancyStatus> EFFECTIVE =
            java.util.EnumSet.of(HELD, CONFIRMED, CHECKED_IN, BLOCKED);

    /** 是否占用"事实上的房间日"：是则参与排斥约束。 */
    public boolean isEffective() {
        return EFFECTIVE.contains(this);
    }

    public boolean belongsTo(OccupancyKind kind) {
        return switch (kind) {
            case RESERVATION -> this != BLOCKED && this != UNBLOCKED;
            case BLOCK -> this == BLOCKED || this == UNBLOCKED;
        };
    }
}
