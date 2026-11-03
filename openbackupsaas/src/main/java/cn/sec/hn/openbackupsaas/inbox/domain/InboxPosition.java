package cn.sec.hn.openbackupsaas.inbox.domain;

import java.util.UUID;

/**
 * 某消费者在某聚合上推进到的位置（S2-02）。
 *
 * <p>同时记版本与序号：只记版本会漏判"同一版本的第二条事件"是重复，从而把它再应用一次。
 *
 * @param lastVersion 已成功应用的最大聚合版本
 * @param lastOrdinal 该版本内已成功应用的最大序号
 */
public record InboxPosition(
        String consumerName,
        UUID tenantId,
        String aggregateType,
        UUID aggregateId,
        Long lastVersion,
        Integer lastOrdinal
) {

    public static InboxPosition start(String consumerName, UUID tenantId, String aggregateType, UUID aggregateId) {
        return new InboxPosition(consumerName, tenantId, aggregateType, aggregateId, 0L, 0);
    }

    /** 空位点等价于"什么都没处理过"：版本 0、序号 0，任何真实事件都比它新。 */
    public long versionOrZero() {
        return lastVersion == null ? 0L : lastVersion;
    }

    public int ordinalOrZero() {
        return lastOrdinal == null ? 0 : lastOrdinal;
    }
}
