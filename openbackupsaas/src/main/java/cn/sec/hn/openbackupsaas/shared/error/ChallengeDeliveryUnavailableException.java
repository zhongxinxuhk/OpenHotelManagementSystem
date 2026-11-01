package cn.sec.hn.openbackupsaas.shared.error;

/**
 * 归属验证的发送通道未接通（ADR-019 §2.10）。
 *
 * <p>生产默认没有任何 {@code OwnershipChallengeSender} 实现（验证码服务商未定）。
 * 显式抛错而不是静默跳过：静默跳过等于声称"验证码已发出"，用户收不到码
 * 还会反复重试，直到挑战过期——比明确失败糟糕得多。
 */
public class ChallengeDeliveryUnavailableException extends RuntimeException {

    public ChallengeDeliveryUnavailableException(String message) {
        super(message);
    }
}
