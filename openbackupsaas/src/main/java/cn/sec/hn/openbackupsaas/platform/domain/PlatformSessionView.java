package cn.sec.hn.openbackupsaas.platform.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 认证时一次查出的平台会话（ADR-019 §2.3）。
 *
 * <p>动作集合不带在这里：它来自另一张表（{@code platform_user_action}），
 * 与"会话是否有效"是两件事。混进一条 SQL 会让人以为动作是会话的一部分，
 * 而实际上动作撤了会话不撤——这个差别必须从类型上就能看出来。
 */
public record PlatformSessionView(
        UUID sessionId,
        UUID userId,
        String username,
        String displayName,
        boolean mustChangePassword,
        OffsetDateTime expiresAt
) {
}
