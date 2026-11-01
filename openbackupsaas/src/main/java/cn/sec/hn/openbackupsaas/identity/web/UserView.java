package cn.sec.hn.openbackupsaas.identity.web;

import cn.sec.hn.openbackupsaas.shared.security.PmsPrincipal;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 当前身份的对外投影（登录响应与 {@code /api/v1/auth/me} 共用）。
 *
 * <p>刻意**不含**密码哈希、会话 id 与令牌：这些字段一旦进了 DTO，
 * 任何一次接口调整都可能把它们带出去。列表字段排序后输出，便于前端与断言稳定。
 *
 * <p>{@code mustChangePassword} 为真表示这个账号还在用管理员发的初始口令，
 * 前端应直接跳到改密页而不是继续渲染业务界面——服务端也会拒绝其它接口，
 * 前端跳转只是为了不让用户对着报错猜。
 */
public record UserView(
        UUID userId,
        UUID tenantId,
        String username,
        String displayName,
        OffsetDateTime sessionExpiresAt,
        List<UUID> hotelIds,
        List<String> permissions,
        boolean mustChangePassword
) {

    public static UserView of(PmsPrincipal principal) {
        List<UUID> hotels = new ArrayList<>(principal.hotelIds());
        hotels.sort(UUID::compareTo);
        List<String> permissions = new ArrayList<>(principal.permissions());
        Collections.sort(permissions);
        return new UserView(principal.userId(), principal.tenantId(), principal.username(),
                principal.displayName(), principal.sessionExpiresAt(), List.copyOf(hotels),
                List.copyOf(permissions), principal.mustChangePassword());
    }
}
