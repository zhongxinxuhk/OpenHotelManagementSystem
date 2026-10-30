package cn.sec.hn.openbackupsaas.inbox.domain;

/**
 * 一条消息的处理结论（S2-02）。
 *
 * <p>四种结论覆盖"必须区分"的四类情况，刻意不合并：
 * <ul>
 *   <li>{@link #PROCESSED}：应用成功，位点已推进。</li>
 *   <li>{@link #IGNORED}：本消费者不处理这种事件类型。<b>不是错误</b>——它说明"事件总线比消费者新"，
 *       记下来才能发现"我们一直在忽略某个事件"。</li>
 *   <li>{@link #SKIPPED_STALE}：旧事件（位点已经超过它）。跳过而不是应用，
 *       否则一次乱序投递就能把新状态覆盖回旧状态。</li>
 *   <li>{@link #PARKED_GAP}：版本缺口，暂时挂起等补齐。<b>不能直接丢弃</b>：
 *       丢掉的可能是唯一一次"订单被确认"的状态迁移。</li>
 * </ul>
 */
public enum InboxOutcome {

    PROCESSED,
    IGNORED,
    SKIPPED_STALE,
    PARKED_GAP;

    /** 是否为结案状态（可结束处理，不阻塞后续消息）。 */
    public boolean settled() {
        return this != PARKED_GAP;
    }
}
