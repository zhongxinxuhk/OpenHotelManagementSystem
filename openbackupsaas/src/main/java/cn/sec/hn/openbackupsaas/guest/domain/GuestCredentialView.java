package cn.sec.hn.openbackupsaas.guest.domain;

import java.time.OffsetDateTime;

/**
 * 住客自己的凭证视图（ADR-019 §2.10）。
 *
 * <p>返回给**本人**，所以 subject 是完整的（本人看自己的手机号/邮箱天经地义）。
 * 管理端与审核队列看到的永远是掩码——那是另一条权限路径，不共用这个视图。
 */
public record GuestCredentialView(
        String provider,
        String subject,
        OffsetDateTime verifiedAt
) {
}
