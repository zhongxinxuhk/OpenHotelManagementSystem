package cn.sec.hn.openbackupsaas.guest.security;

import cn.sec.hn.openbackupsaas.guest.domain.GuestVerificationStatus;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 住客认证结果（ADR-019 §2.3）。
 *
 * <p><b>刻意不实现 {@code PmsPrincipal}</b>（那是员工主体）：它带
 * {@code tenantId} / {@code hotelIds} / {@code permissions}。让住客主体上出现"租户字段"，
 * 哪怕一直是 null，也会引出"这个住客属于哪家店"这类迟早被填上的问题——
 * 而那正是越权的起点。两条平面的主体类型不同，写错就是编译不过。
 *
 * <p>{@code verificationStatus} 每次请求现读库，不在会话里缓存：
 * 人工审核通过后，住客下一次请求就解开限制，不需要重新登录，
 * 也不存在"会话里的旧状态"与库里不一致的窗口。
 */
public record GuestPrincipal(
        UUID sessionId,
        UUID accountId,
        String displayName,
        GuestVerificationStatus verificationStatus,
        OffsetDateTime sessionExpiresAt
) implements Principal {

    /** Spring Security 要一个非空 name；用主身份 id，不用手机号（那属于凭证，且是个人信息）。 */
    @Override
    public String getName() {
        return accountId.toString();
    }
}
