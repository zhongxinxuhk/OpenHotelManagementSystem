package cn.sec.hn.openbackupsaas.guest.ownership;

/**
 * 归属挑战的发送通道（ADR-019 §2.10）。
 *
 * <p>这是**接口形状**：验证码服务商（短信/邮件）未定，接通时只需提供一个实现 +
 * 在管理员控制台把对应方式的开关打开，机制（挑战表、尝试上限、一次性消费）零改动。
 *
 * <p><b>实现者必须遵守</b>：
 * <ul>
 *   <li>验证码只经本接口交给通道，实现不得把它写进数据库、日志或响应体；</li>
 *   <li>发送失败要抛异常（服务层据此把挑战标记为失败），不能吞掉——
 *       吞掉后用户收不到码还以为发了，重试若干次后挑战过期，体验极差。</li>
 * </ul>
 *
 * <p>生产默认**没有任何实现**：此时发起挑战会得到 503"发送通道未接通"，
 * 而不是静默跳过发送——静默跳过等于声称"验证码已发出"，比失败更糟。
 */
public interface OwnershipChallengeSender {

    /**
     * 发送验证码。
     *
     * @param delivery 含目标方式、规范化后的目标标识、明文验证码（仅在此处出现）
     */
    void send(ChallengeDelivery delivery);

    /** 一次投递。{@code code} 的明文只应存在于本对象的存活期内。 */
    record ChallengeDelivery(
            String provider,
            String subject,
            String code,
            java.time.OffsetDateTime expiresAt
    ) {
    }
}
