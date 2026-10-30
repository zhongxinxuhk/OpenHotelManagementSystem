package cn.sec.hn.openbackupsaas.integration.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 一条被领取到的外部调用任务（S2-03）。
 *
 * <p>只带执行所需的字段：{@code payload} 是入队时序列化好的请求内容，
 * 执行器<b>不回查业务表</b>——回查会引入"执行时看到的是更新后的数据"这种不可复现的问题。
 */
public record ExternalTask(
        UUID taskId,
        UUID tenantId,
        UUID hotelId,
        String taskType,
        String channel,
        String idempotencyKey,
        String payload,
        TaskStatus status,
        Integer attempts,
        Integer maxAttempts,
        OffsetDateTime nextAttemptAt,
        UUID leaseToken,
        String resultRef,
        String lastError
) {

    public int attemptsOrZero() {
        return attempts == null ? 0 : attempts;
    }

    public int maxAttemptsOrDefault() {
        return maxAttempts == null || maxAttempts < 1 ? 1 : maxAttempts;
    }
}
