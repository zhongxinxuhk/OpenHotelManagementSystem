package cn.sec.hn.openbackupsaas.journal.domain;

/**
 * 库存效果种类（S1-06）。与占用种类对应，但**不等于**占用种类：
 * 这里表达的是"这一晚因为什么不可售"，供库存核对与投影重建使用。
 */
public enum EffectKind {
    RESERVATION,
    BLOCK
}
