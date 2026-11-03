package cn.sec.hn.openbackupsaas.journal.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 关键审计（S1-06）。
 *
 * <p>target 是**多态引用**：一张外键表约束不了所有目标类型，归属与存在性由服务层校验，
 * 这里不假装一个 FK 已经保证了什么。
 *
 * <p>{@code changeSummary} 只放最小必要字段（哪个区间、哪个状态），
 * 不写口令、令牌、证件号一类敏感原文。
 */
public record AuditLog(
        UUID id,
        UUID tenantId,
        UUID hotelId,
        String action,
        ActorType actorType,
        UUID actorId,
        String targetType,
        UUID targetId,
        Long beforeVersion,
        Long afterVersion,
        String changeSummary,
        OffsetDateTime occurredAt,
        OffsetDateTime createdAt
) {

    public static AuditLog of(UUID tenantId, UUID hotelId, String action, ActorType actorType, UUID actorId,
                              String targetType, UUID targetId, Long beforeVersion, Long afterVersion,
                              String changeSummary, OffsetDateTime now) {
        return new AuditLog(UUID.randomUUID(), tenantId, hotelId, action, actorType, actorId,
                targetType, targetId, beforeVersion, afterVersion, changeSummary, now, now);
    }
}
