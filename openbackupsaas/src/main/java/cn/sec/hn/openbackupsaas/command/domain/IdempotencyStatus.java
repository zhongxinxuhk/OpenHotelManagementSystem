package cn.sec.hn.openbackupsaas.command.domain;

/**
 * 幂等记录状态（S1-07）。
 *
 * <p>{@code IN_PROGRESS} 不是"失败"：它表示"已占位、业务结果未知"。
 * 处理过程中崩溃时，若业务事务已提交则记录随之提交为 SUCCEEDED；
 * 若业务回滚则这一行也不存在——不存在"记录说在做、业务其实没做"的谎言。
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this != IN_PROGRESS;
    }
}
