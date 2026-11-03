package cn.sec.hn.openbackupsaas.integration.domain;

/**
 * 外部调用任务的持久化状态（S2-03）。
 *
 * <pre>
 *  PENDING ──领取──▶ RUNNING ──成功──▶ SUCCEEDED
 *     ▲                 │
 *     └──退避/限流──────┤
 *                       ├──永久错误──▶ FAILED        （不再重试）
 *                       ├──次数耗尽──▶ DEAD          （等人工，S2-04 重放）
 *                       └──未知且无法查询──▶ MANUAL_REVIEW（转人工核对）
 * </pre>
 *
 * <p>为什么把 {@code FAILED}（永久）与 {@code DEAD}（次数耗尽）分开：
 * 前者是"对方明确拒绝，再试也没用"，后者是"试到没机会了，可能再试有用"。
 * 运维处置完全相反——前者要改数据后重新入队，后者直接重放即可（S2-04）。
 */
public enum TaskStatus {

    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    MANUAL_REVIEW,
    DEAD;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == MANUAL_REVIEW || this == DEAD;
    }
}
