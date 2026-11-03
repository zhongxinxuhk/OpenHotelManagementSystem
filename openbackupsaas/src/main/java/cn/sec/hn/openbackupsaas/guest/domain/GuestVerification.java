package cn.sec.hn.openbackupsaas.guest.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 实名档案的**对外可读视图**（ADR-018 §2.4）。
 *
 * <p>注意这个记录里**没有**身份证号原文、也没有密文：它只带掩码。
 * 解密需要拿 {@code accountId} 走受审计的取证流程，不随普通查询返回——
 * 让"能读到档案"和"能看到完整证件号"变成两件不同成本的事。
 */
public record GuestVerification(
        UUID accountId,
        String idNumberMask,
        String realNameMask,
        GuestVerificationStatus status,
        OffsetDateTime submittedAt,
        OffsetDateTime verifiedAt
) {
}
