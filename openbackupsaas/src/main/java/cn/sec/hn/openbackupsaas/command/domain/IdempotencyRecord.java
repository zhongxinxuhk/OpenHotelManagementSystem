package cn.sec.hn.openbackupsaas.command.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 幂等记录（S1-07）。
 *
 * <p>只存**请求摘要**，不存请求原文：客户端重试时把同样的字段再算一遍摘要即可比对，
 * 而库里不会因为保存了完整请求体而多出一份需要保护的敏感数据。
 */
public record IdempotencyRecord(
        UUID operationId,
        UUID tenantId,
        UUID hotelId,
        String callerNamespace,
        String commandName,
        String idempotencyKey,
        Integer hashVersion,
        String requestHash,
        IdempotencyStatus status,
        String resultCode,
        UUID resultRef,
        String responseSnapshot,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {

    public static IdempotencyRecord begin(UUID operationId, UUID tenantId, UUID hotelId,
                                          String callerNamespace, String commandName,
                                          String idempotencyKey, int hashVersion, String requestHash) {
        return new IdempotencyRecord(operationId, tenantId, hotelId, callerNamespace, commandName,
                idempotencyKey, hashVersion, requestHash, IdempotencyStatus.IN_PROGRESS, null, null, null,
                null, null);
    }
}
