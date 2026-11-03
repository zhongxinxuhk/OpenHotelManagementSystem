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
 *
 * <p>{@code mustChangePassword}（V13）也走这条查询，理由相同：它是"这个会话现在
 * 能不能用来干业务"的一部分，取会话时一并拿到，就不需要在每个业务入口再查一次库。
 * 代价是每请求多读一列，换来的是"任何一条业务路径都拿不到被豁免的机会"。
 */
public record SessionView(
        UUID sessionId,
        UUID tenantId,
        UUID userId,
        String username,
        String displayName,
        OffsetDateTime expiresAt,
        boolean mustChangePassword
) {
}
