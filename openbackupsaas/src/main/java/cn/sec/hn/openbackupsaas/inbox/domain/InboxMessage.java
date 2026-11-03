package cn.sec.hn.openbackupsaas.inbox.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 消费侧收到的一条事件（S2-02）。
 *
 * <p>字段与发布侧 {@code OutboxEnvelope} 一一对应，但这里是<b>不可信输入</b>：
 * 消息可能重复、乱序、过期，甚至来自别的租户（配置错误或人为投毒）。
 * 因此构造时做完整校验，缺字段直接拒绝，不做"缺了就用默认值"的宽容解析——
 * 宽容解析会把一条坏消息变成一条"看起来正常但语义错误"的事实。
 *
 * <p>{@code payload} 保留原始 JSON 文本：消费者按自己需要解析，Inbox 只负责搬运与存证。
 *
 * <p>{@code tenantId} 从消息体取，而不是从线程上下文取：Worker 线程没有请求上下文，
 * 谁在处理这条消息，就该用这条消息自己的租户——这也是"异步线程显式传递授权上下文"的落地方式。
 */
public record InboxMessage(
        UUID eventId,
        UUID tenantId,
        UUID hotelId,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        int eventOrdinal,
        String eventType,
        int schemaVersion,
        String payload,
        OffsetDateTime occurredAt
) {

    public InboxMessage {
        if (eventId == null || tenantId == null || aggregateId == null) {
            throw new IllegalArgumentException("事件缺少 eventId / tenantId / aggregateId");
        }
        if (aggregateType == null || aggregateType.isBlank() || eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("事件缺少 aggregateType / eventType");
        }
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion 不能为负：" + aggregateVersion);
        }
        if (eventOrdinal < 1) {
            throw new IllegalArgumentException("eventOrdinal 从 1 开始：" + eventOrdinal);
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("事件缺少 payload");
        }
    }

    /** 是否比给定位点更新（用于"重复 / 旧事件"判定）。 */
    public boolean isNewerThan(long lastVersion, int lastOrdinal) {
        return aggregateVersion > lastVersion
                || (aggregateVersion == lastVersion && eventOrdinal > lastOrdinal);
    }

    /**
     * 是否为版本缺口：比当前位点跳过了至少一个版本。
     *
     * <p>同版本内的序号跳跃不算缺口——一个版本可能只产生 1 条事件（例如订单创建），
     * 下一条同版本事件根本不存在。真正的缺口只有"整版丢失"这一种。
     */
    public boolean isGapAfter(long lastVersion) {
        return aggregateVersion > lastVersion + 1;
    }
}
