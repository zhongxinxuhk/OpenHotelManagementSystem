package cn.sec.hn.openbackupsaas.ops.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 一条接不下来的消息（S2-04）。
 *
 * <p>{@code messageBody} 是原始报文，重放要用它；改写它就等于重放一条从未存在过的消息。
 * {@code tenantId} 允许为空：解析失败时可能连租户都取不出来，宁可记不全也不能因此丢掉报文。
 */
public record DeadLetter(
        UUID deadLetterId,
        UUID tenantId,
        String source,
        String messageBody,
        String error,
        DeadLetterStatus status,
        OffsetDateTime receivedAt
) {
}
