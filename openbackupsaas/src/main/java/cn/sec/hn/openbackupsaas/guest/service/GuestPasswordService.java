package cn.sec.hn.openbackupsaas.guest.service;

import cn.sec.hn.openbackupsaas.guest.domain.GuestProvider;
import cn.sec.hn.openbackupsaas.guest.domain.PasswordResetChallenge;
import cn.sec.hn.openbackupsaas.guest.mapper.GuestMapper;
import cn.sec.hn.openbackupsaas.guest.ownership.ChallengeSenderRouter;
import cn.sec.hn.openbackupsaas.guest.ownership.OwnershipChallengeSender;
import cn.sec.hn.openbackupsaas.identity.domain.PasswordPolicy;
import cn.sec.hn.openbackupsaas.shared.error.ChallengeDeliveryUnavailableException;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.shared.error.UnauthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 住客口令：已登录改密 + 忘记密码重置（R-20261103-01）。
 *
 * <h2>两个流程为什么在一个服务里</h2>
 * 两者最终都落在同一行（USERNAME 凭证的 secret_hash）且共用同一套策略
 * （{@link PasswordPolicy}），放在一起让"改密的全部后果"只有一处实现：
 * 哈希更新、会话撤销、日志口径。区别只在**入口信任**：
 * <ul>
 *   <li><b>已登录改密</b>：会话就是身份证明——校验**当前口令**（防 cookie 被盗后
 *       直接换锁），撤销<b>其它</b>会话、保留当前（与员工/平台同一语义）；</li>
 *   <li><b>忘记密码重置</b>：匿名——EMAIL 验证码是身份证明。码校验通过后
 *       撤销<b>全部</b>会话且**不自动登录**（匿名流程没有"当前会话"，
 *       强制重新登录是标准姿态；也让新口令从第一次使用就被验证）。</li>
 * </ul>
 *
 * <h2>重置挑战为什么是第三张表（不复用登录挑战）</h2>
 * 除 R-20261102-07 已记录的"可用性不联动"（重置<b>不受</b> EMAIL 登录开关约束——
 * 账号恢复 ≠ 登录方式；只受通道可用性约束，SMTP 未接通即 503）外，多一条：
 * <b>码按用途绑定</b>——登录挑战的码只能登录，重置挑战的码只能重置口令。
 * 共表时攻击者发起"登录挑战"后诱骗用户读出邮件里的码（邮件写的是"登录验证码"），
 * 即可拿去重置口令；分表后 challengeId 只在对应流程内有效，且重置邮件正文
 * 明示操作类型（{@code ChallengeDelivery.purpose}）。
 *
 * <h2>枚举防护（沿用已确认口径：照发码 + 统一文案）</h2>
 * 未注册邮箱也照发验证码，响应形态与已注册一致；提交码时
 * "未注册 / 码错 / 账户关闭 / 账户无口令凭证"统一为
 * "验证码或邮箱不正确"（401）——重置接口与登录接口同样不构成枚举器。
 */
@Service
public class GuestPasswordService {

    private static final Logger log = LoggerFactory.getLogger(GuestPasswordService.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final GuestMapper guestMapper;
    private final ChallengeSenderRouter senderRouter;
    private final ResetChallengeAttemptRecorder attemptRecorder;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public GuestPasswordService(GuestMapper guestMapper,
                                ChallengeSenderRouter senderRouter,
                                ResetChallengeAttemptRecorder attemptRecorder,
                                PasswordEncoder passwordEncoder,
                                Clock clock) {
        this.guestMapper = guestMapper;
        this.senderRouter = senderRouter;
        this.attemptRecorder = attemptRecorder;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    // ==================== 已登录改密 ====================

    /**
     * 修改口令（已登录）。
     *
     * <p>校验**当前口令**：会话可能是被盗的 cookie，口令是第二因子——
     * 不校验就等于"捡到会话即可换锁"。通过后撤销<b>其它</b>会话、保留当前。
     *
     * @throws UnauthenticatedException 当前口令不正确（统一一句，不区分账户有无口令凭证的细节差异中可探测者）
     * @throws ConflictException 该账户没有 USERNAME 凭证（无口令可改——先绑定用户名）
     */
    @Transactional
    public void changePassword(UUID accountId, UUID currentSessionId,
                               String currentPassword, String newPassword) {
        var credential = guestMapper.findUsernameCredentialOfAccount(accountId)
                .orElseThrow(() -> new ConflictException(
                        "该账户未设置口令（无用户名凭证），请先绑定用户名后再修改口令"));

        if (!passwordEncoder.matches(currentPassword == null ? "" : currentPassword,
                credential.secretHash())) {
            // 与登录同一条纪律：不区分"口令错"与其它失败形态
            throw new UnauthenticatedException("当前口令不正确");
        }

        // 策略校验带上用户名："新口令不能包含登录名"
        PasswordPolicy.requireAcceptable(newPassword, credential.subject());

        guestMapper.updateUsernameSecret(accountId,
                passwordEncoder.encode(newPassword), OffsetDateTime.now(clock));

        // 踢其它设备、保本会话——与员工/平台改密同一语义。
        int revoked = guestMapper.revokeAllSessionsExcept(accountId, currentSessionId,
                "GUEST_PASSWORD_CHANGED", OffsetDateTime.now(clock));
        log.info("guest password changed accountId={} otherSessionsRevoked={}",
                accountId, revoked);
    }

    // ==================== 忘记密码：发起重置挑战 ====================

    /**
     * 匿名发起重置挑战：向目标邮箱发 6 位验证码。
     *
     * <p>**不查邮箱是否注册**（枚举防护）：未注册邮箱照发码，响应形态与已注册一致。
     * 也不查 EMAIL 登录开关（账号恢复 ≠ 登录方式，可用性不联动）——
     * 唯一的前置是发送通道（未接通 → 503）。
     */
    @Transactional
    public ResetChallengeIssued requestResetChallenge(String rawEmail) {
        String email = GuestProvider.EMAIL.normalize(rawEmail);

        OffsetDateTime now = OffsetDateTime.now(clock);
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        UUID challengeId = UUID.randomUUID();
        OffsetDateTime expiresAt = now.plusMinutes(PasswordResetChallenge.TTL_MINUTES);

        guestMapper.insertResetChallenge(challengeId, GuestProvider.EMAIL.name(),
                email, sha256(code), expiresAt, now);

        // purpose 写进邮件正文（抗钓鱼）：用户被诱骗读码时，
        // "重置登录口令"比一句"验证码"更能引起警觉
        senderRouter.send(new OwnershipChallengeSender.ChallengeDelivery(
                GuestProvider.EMAIL.name(), email, code, expiresAt, "重置登录口令"));

        log.info("guest password reset challenge issued subject={} challengeId={}",
                mask(email), challengeId);
        return new ResetChallengeIssued(challengeId, mask(email), expiresAt);
    }

    /**
     * 用验证码完成口令重置。
     *
     * <p>码校验通过 → 按 (EMAIL, subject) 定位 ACTIVE 账户 → 取其 USERNAME 凭证 →
     * 更新哈希 → **撤销全部会话**（匿名流程没有"当前会话"可保留）。
     * 不自动登录：强制用新口令重新登录，新口令从第一次使用就被验证。
     *
     * @throws ConflictException 挑战已消费 / 超次 / 过期（需重新发起）
     * @throws UnauthenticatedException 统一文案：码错 / 邮箱未注册 / 账户关闭 / 账户无口令凭证
     */
    @Transactional
    public void completeReset(UUID challengeId, String code, String newPassword) {
        PasswordResetChallenge challenge = guestMapper.findResetChallenge(challengeId)
                .orElseThrow(() -> new ConflictException("验证码已失效，请重新发起重置"));
        if (challenge.isConsumed()) {
            throw new ConflictException("该验证码已使用，请重新发起重置");
        }
        if (challenge.isExhausted()) {
            throw new ConflictException("错误次数已达上限，请重新发起重置");
        }
        if (challenge.isExpiredAt(OffsetDateTime.now(clock))) {
            throw new ConflictException("验证码已过期，请重新发起重置");
        }

        if (!constantTimeEquals(code == null ? "" : code.trim(), challenge.codeHash())) {
            // 独立事务计数：本事务即将回滚，同事务计数会被卷走
            attemptRecorder.recordFailure(challengeId);
            throw new UnauthenticatedException("验证码或邮箱不正确");
        }

        int consumed = guestMapper.consumeResetChallenge(challengeId);
        if (consumed == 0) {
            throw new ConflictException("该验证码已使用，请重新发起重置");
        }

        // 码对了，但邮箱可能未注册 / 账户关闭 / 账户没解绑过用户名——
        // 这些分支对调用方是可探测信息，统一为与"码错"同一句。
        UUID accountId = guestMapper
                .findActiveAccountIdByCredential(GuestProvider.EMAIL.name(), challenge.subject())
                .orElseThrow(() -> {
                    log.info("guest password reset failed: no active credential subject={}",
                            mask(challenge.subject()));
                    return new UnauthenticatedException("验证码或邮箱不正确");
                });
        var credential = guestMapper.findUsernameCredentialOfAccount(accountId)
                .orElseThrow(() -> {
                    log.info("guest password reset failed: no username credential subject={}",
                            mask(challenge.subject()));
                    return new UnauthenticatedException("验证码或邮箱不正确");
                });

        PasswordPolicy.requireAcceptable(newPassword, credential.subject());
        guestMapper.updateUsernameSecret(accountId,
                passwordEncoder.encode(newPassword), OffsetDateTime.now(clock));

        // 撤销全部会话：口令被重置意味着"旧口令持有者"（可能包括攻击者）
        // 不应再保有会话。不自动登录——见类注释。
        int revoked = guestMapper.revokeAllSessionsOfAccount(
                accountId, "GUEST_PASSWORD_RESET", OffsetDateTime.now(clock));
        log.info("guest password reset completed subject={} sessionsRevoked={}",
                mask(challenge.subject()), revoked);
    }

    // ==================== 内部 ====================

    /** 常量时间比对：防按位猜测（与绑定/登录挑战同一实现纪律）。 */
    private static boolean constantTimeEquals(String submitted, String expectedHash) {
        byte[] a = sha256(submitted).getBytes(StandardCharsets.US_ASCII);
        byte[] b = expectedHash.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(a, b);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不提供 SHA-256", ex);
        }
    }

    /** 邮箱掩码：首字符 + *** + @域名（与绑定/登录挑战同规则）。 */
    private static String mask(String subject) {
        int at = subject.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return subject.charAt(0) + "***" + subject.substring(at);
    }

    /** 重置挑战发起结果。**不含验证码**——码只经发送通道走。 */
    public record ResetChallengeIssued(
            UUID challengeId,
            String subjectMasked,
            OffsetDateTime expiresAt
    ) {
    }
}
