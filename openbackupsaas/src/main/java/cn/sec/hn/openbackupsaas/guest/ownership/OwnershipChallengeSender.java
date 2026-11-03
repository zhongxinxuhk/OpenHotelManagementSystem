package cn.sec.hn.openbackupsaas.guest.ownership;

/**
 * 归属挑战的发送通道（ADR-019 §2.10）。
 *
 * <p>这是<b>接口形状</b>：一个实现负责一条（或多条）登录方式。EMAIL 已由
 * {@code MailOwnershipChallengeSender}（SMTP）接通；PHONE（短信）等服务商签约；
 * 第三方（微信/QQ/统一身份/Apple）的归属证明是 OAuth 回调，不走本接口。
 * 接通新的方式只需提供一个实现并在管理员控制台打开对应开关，
 * 机制（挑战表、尝试上限、一次性消费）零改动。
 *
 * <p><b>实现者必须遵守</b>：
 * <ul>
 *   <li>验证码只经本接口交给通道，实现不得把它写进数据库、日志或响应体；</li>
 *   <li>发送失败要抛异常（服务层据此把挑战标记为失败），不能吞掉——
 *       吞掉后用户收不到码还以为发了，重试若干次后挑战过期，体验极差。</li>
 * </ul>
 *
 * <p>「方式 → 实现」的分发由 {@link ChallengeSenderRouter} 完成：按
 * {@link #supports(String)} 与 Spring 的 {@code @Order}（值小者优先）选第一个
 * 支持该方式的实现。<b>没有任何实现支持该方式时是 503</b>，
 * 而不是静默跳过发送——静默跳过等于声称"验证码已发出"，比失败更糟。
 */
public interface OwnershipChallengeSender {

    /**
     * 本实现是否负责 {@code provider}（取 {@code GuestProvider.name()}）。
     *
     * <p>默认「负责」是为兼容最初"一个实现包办全部验证码方式"的形状；
     * 真实通道实现应精确声明——{@code MailOwnershipChallengeSender} 只认 EMAIL，
     * 短信实现将来只认 PHONE。声明得过宽会把别的方式的码发进错误通道。
     */
    default boolean supports(String provider) {
        return true;
    }

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
            java.time.OffsetDateTime expiresAt,
            String purpose
    ) {
    }
}
