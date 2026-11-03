package cn.sec.hn.openbackupsaas.guest.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 实名决定留痕（ADR-019 §2.5）。
 *
 * <p>为什么它不复用档案表：档案会被"重新提交"覆盖（换证件号、改名）。
 * 一旦覆盖，上一次为什么通过或驳回就永久丢失——而合规要求恰恰是"事后能说清
 * 当时是谁、依据什么做的决定"。所以留痕表只增不改，且**自带掩码快照**：
 * 审核的是哪一份资料，事后不需要靠档案反推（那时的档案可能已经不是它了）。
 */
public record GuestVerificationReview(
        UUID id,
        UUID accountId,
        String idNumberMask,
        String realNameMask,
        GuestVerificationStatus decision,
        String source,
        String reason,
        String detail,
        UUID reviewerId,
        String reviewerName,
        OffsetDateTime decidedAt
) {

    /** 人工决定来源常量；权威渠道的决定来源形如 {@code AUTHORITY:<channelCode>}。 */
    public static final String SOURCE_MANUAL = "MANUAL";

    public static String authoritySource(String channelCode) {
        return "AUTHORITY:" + channelCode;
    }
}
