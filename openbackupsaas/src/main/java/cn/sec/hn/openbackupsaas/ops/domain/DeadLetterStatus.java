package cn.sec.hn.openbackupsaas.ops.domain;

/**
 * 死信状态（S2-04）。
 *
 * <p>{@code DISMISSED} 是"确认不必处理"的终态，必须带原因——否则半年后没人说得清
 * 这条消息为什么被扔掉。它与"重放成功"分开记，因为两者的责任完全不同。
 */
public enum DeadLetterStatus {
    PENDING,
    REPLAYED,
    DISMISSED
}
