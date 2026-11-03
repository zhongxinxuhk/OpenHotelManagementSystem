package cn.sec.hn.openbackupsaas.shared.error;

/**
 * 归属验证的发送通道未接通或投递失败（ADR-019 §2.10）。
 *
 * <p>两种形态都是它：没有任何实现声明负责该方式（短信服务商未定、邮件通道未开启），
 * 或实现尝试投递但失败（SMTP 拒收、中继不可达）。对调用方两者处置相同：
 * 503「稍后再试或换方式」。
 *
 * <p>显式抛错而不是静默跳过：静默跳过等于声称"验证码已发出"，用户收不到码
 * 还会反复重试，直到挑战过期——比明确失败糟糕得多。
 */
public class ChallengeDeliveryUnavailableException extends RuntimeException {

    public ChallengeDeliveryUnavailableException(String message) {
        super(message);
    }

    /** 投递失败形态：保留根因（连接拒绝/超时等），但异常链中不得出现验证码。 */
    public ChallengeDeliveryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
