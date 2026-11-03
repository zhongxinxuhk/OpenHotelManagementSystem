package cn.sec.hn.openbackupsaas.guest.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 审核队列项（ADR-019 §2.5）。
 *
 * <p><b>只有掩码</b>。审核员判断"证件是否清晰合规、姓名是否与证件一致"不需要完整号码；
 * 需要完整号码的场景（例如涉诉取证）走独立的受审计流程，不在这个队列里。
 * 队列能拿到明文，等于把"读队列"这个日常动作升级成"接触全部实名信息"。
 */
public record PendingVerification(
        UUID accountId,
        String idNumberMask,
        String realNameMask,
        GuestVerificationStatus status,
        OffsetDateTime submittedAt
) {
}
