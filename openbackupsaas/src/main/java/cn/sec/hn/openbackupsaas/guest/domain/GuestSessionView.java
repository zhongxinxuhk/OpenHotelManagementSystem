package cn.sec.hn.openbackupsaas.guest.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 认证时一次查出的会话 + 账号信息（ADR-019 §2.3）。
 *
 * <p>为什么把 {@code displayName} 一起查出来：认证在**每个请求**上都要跑，
 * 再单独查一次账号会多一次往返。这里没有权限/门店字段——住客没有那些东西，
 * 实名状态另行单查（它要参与判定，单独一次查询更容易看清成本）。
 */
public record GuestSessionView(
        UUID sessionId,
        UUID accountId,
        String displayName,
        OffsetDateTime expiresAt
) {
}
