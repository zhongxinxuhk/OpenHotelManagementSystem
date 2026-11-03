package cn.sec.hn.openbackupsaas.reconcile.domain;

/**
 * 差异分级（S2-05）。分级决定"谁能修"，不是决定"谁负责"。
 *
 * <table>
 *   <caption>三级的处置边界</caption>
 *   <tr><th>分级</th><th>含义</th><th>可以自动修复吗</th></tr>
 *   <tr><td>{@code DERIVED}</td><td>派生数据与权威记录不一致（投影）</td><td>可以：按权威重建，幂等</td></tr>
 *   <tr><td>{@code FACT}</td><td>交易事实之间互相矛盾（订单/占用/流水）</td><td><b>绝对不可以</b></td></tr>
 *   <tr><td>{@code BACKLOG}</td><td>投递或消费积压（事件发不出去、消息接不下来）</td><td>修数据没用，靠重试与扩容</td></tr>
 * </table>
 *
 * <p>为什么把 BACKLOG 单列：它看起来像"故障"，但数据本身完全正确。把积压当成数据差异去"修复"，
 * 只会掩盖真正的投递问题（例如对方 Broker 不可用），让积压继续增长而看板显示"已修复"。
 */
public enum FindingSeverity {

    DERIVED,
    FACT,
    BACKLOG;

    /** 该分级是否允许自动修复；真正的白名单在 {@link FindingKind} 里逐项声明。 */
    public boolean allowsAutomaticRepair() {
        return this == DERIVED;
    }
}
