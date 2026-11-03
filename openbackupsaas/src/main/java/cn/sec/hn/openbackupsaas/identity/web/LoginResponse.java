package cn.sec.hn.openbackupsaas.identity.web;

import java.time.OffsetDateTime;

/**
 * 登录成功响应。
 *
 * <p>{@code token} 是明文令牌，只在这一次响应里出现；服务端只保留其 SHA-256。
 * 客户端把它放进 {@code Authorization: Bearer <token>}，不要塞进 URL（会进访问日志）。
 */
public record LoginResponse(String token, OffsetDateTime expiresAt, UserView user) {
}
