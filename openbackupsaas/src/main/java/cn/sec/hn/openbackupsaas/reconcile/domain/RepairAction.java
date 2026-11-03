package cn.sec.hn.openbackupsaas.reconcile.domain;

/**
 * 修复动作白名单（S2-05）。
 *
 * <p><b>只有一种动作。</b>这不是"还没实现别的手段"，而是刻意的：对账能安全做的
 * 唯一一件事是"按权威记录重算派生表"。任何"改状态"、"补订单"、"补流水"之类的动作
 * 都会让对账从"发现问题"变成"制造事实"——一次错误的重算就会把真实的交易记录改写掉，
 * 而这种损失无法从日志里恢复。
 *
 * <p>因此接口上只有重建与不修两种取值。要"修"事实类差异，只能由人在业务系统里
 * 走正式流程（例如补做一笔订单），然后由对账扫到差异消失、工单自动退场。
 */
public enum RepairAction {

    /**
     * 按权威记录重建派生数据（当前是房态投影）。
     *
     * <p>重建是幂等的：同一窗口连续重建两次结果一致。这也是它可以自动执行的前提——
     * 自动修复必须"修错了也能再修一次"。
     */
    REBUILD_PROJECTION("重建房态投影"),

    /** 不修，只能人工判断。 */
    NONE("不可自动修复");

    private final String description;

    RepairAction(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    public boolean isAutomatic() {
        return this != NONE;
    }
}
