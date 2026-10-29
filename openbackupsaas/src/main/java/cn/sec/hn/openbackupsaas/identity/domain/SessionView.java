package cn.sec.hn.openbackupsaas.identity.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 会话校验的联表结果（会话 × 账号 × 租户），S1-03。
 *
 * <p>为什么一次查完而不是"先查会话再查账号"：可用性判定同时依赖三方状态
 * （会话未撤销未过期、账号 ACTIVE、租户 ACTIVE）。分开查会出现
 * "会话有效但账号刚被停用"的窗口期，合并成一条带条件的 SQL 后，
 * 判定在数据库里一次完成，不存在中间态。
 */
public record SessionView(
        UUID sessionId,
        UUID tenantId,
        UUID userId,
        String username,
        String displayName,
        OffsetDateTime expiresAt
) {
}
