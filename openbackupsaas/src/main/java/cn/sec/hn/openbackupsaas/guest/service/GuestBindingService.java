package cn.sec.hn.openbackupsaas.guest.service;

import cn.sec.hn.openbackupsaas.guest.domain.GuestCredentialView;
import cn.sec.hn.openbackupsaas.guest.domain.GuestProvider;
import cn.sec.hn.openbackupsaas.guest.domain.OwnershipChallenge;
import cn.sec.hn.openbackupsaas.guest.mapper.GuestMapper;
import cn.sec.hn.openbackupsaas.guest.ownership.OwnershipChallengeSender;
import cn.sec.hn.openbackupsaas.shared.error.ChallengeDeliveryUnavailableException;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 住客凭证绑定（ADR-019 §2.10）。
 *
 * <p>需求原话："有账户后，可以绑定其他第三方登录源"。绑定的核心安全问题
 * 是"如何证明这个手机号/邮箱是你的"——答案是**挑战-响应**：
 * 向目标发起验证码挑战，提交正确的码才允许绑定。
 *
 * <p>三条不可妥协的约束（都有测试钉死）：
 * <ol>
 *   <li><b>挑战归属发起者</b>：confirm 时校验挑战的 account_id 就是当前会话的人。
 *       缺了这条，拿到别人的 challengeId 就能把自己的手机号绑到别人账户（账户接管）；</li>
 *   <li><b>防爆破三件套</b>：码哈希存储 + 5 次尝试上限 + 10 分钟过期。
 *       6 位数字熵只有 20 bit，任何一条单独存在都不够；</li>
 *   <li><b>解绑自保护</b>：不能解绑最后一个凭证——那是把自己锁在门外，
 *       且住客没有"平台帮恢复"这条路（A-05 未建）。</li>
 * </ol>
 */
@Service
public class GuestBindingService {

    private static final Logger log = LoggerFactory.getLogger(GuestBindingService.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final GuestMapper guestMapper;
    private final GuestAccountService accountService;
    private final ChallengeAttemptRecorder attemptRecorder;
    private final ObjectProvider<OwnershipChallengeSender> senderProvider;
    private final Clock clock;

    public GuestBindingService(GuestMapper guestMapper,
                               GuestAccountService accountService,
                               ChallengeAttemptRecorder attemptRecorder,
                               ObjectProvider<OwnershipChallengeSender> senderProvider,
                               Clock clock) {
        this.guestMapper = guestMapper;
        this.accountService = accountService;
        this.attemptRecorder = attemptRecorder;
        this.senderProvider = senderProvider;
        this.clock = clock;
    }

    /**
     * 发起归属挑战：生成 6 位验证码、落库（哈希）、经发送通道投递。
     *
     * <p>发送通道未接通时 503——**绝不静默跳过**。静默跳过等于声称
     * "验证码已发出"，用户收不到码还会反复重试，直到挑战过期。
     *
     * @throws LoginMethodUnavailableException 方式未开放（实现 ∧ 开关双条件）
     * @throws ConflictException 目标已被其它账户占用（提前失败，不浪费一次发送）
     */
    @Transactional
    public ChallengeIssued issueChallenge(UUID accountId, GuestProvider provider, String rawSubject) {
        if (provider == GuestProvider.USERNAME) {
            // 用户名的归属证明是"知道密码"，不是验证码——走 bindUsername。
            throw new IllegalArgumentException("用户名绑定不需要归属挑战，请使用密码绑定接口");
        }
        // 绑定只看**能力**（有没有发送通道），不看登录开关：
        // guest_login_policy 挡的是"用这种方式登录"，而绑定是"往账户加凭证"。
        // 混用会让"运营临时关停 PHONE 登录"连带把"绑定手机号"也堵死——
        // 那两个动作的可用性本来就不该联动。绑完能不能用，由登录时的开关决定。
        //
        // 挑战只适用于**验证码可达**的方式（PHONE/EMAIL）。
        // 第三方（微信/QQ/统一身份/Apple）的归属证明是 OAuth 回调，
        // 走验证码等于问对方要密码——语义就是错的。
        if (provider != GuestProvider.PHONE && provider != GuestProvider.EMAIL) {
            throw new cn.sec.hn.openbackupsaas.shared.error.LoginMethodUnavailableException(
                    "该登录方式通过授权回调绑定，暂未开放");
        }
        String subject = provider.normalize(rawSubject);
        assertSubjectFree(provider, subject);

        OffsetDateTime now = OffsetDateTime.now(clock);
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        UUID challengeId = UUID.randomUUID();
        OffsetDateTime expiresAt = now.plusMinutes(OwnershipChallenge.TTL_MINUTES);

        guestMapper.insertChallenge(challengeId, accountId, provider.name(), subject,
                sha256(code), expiresAt, now);

        OwnershipChallengeSender sender = senderProvider.getIfAvailable();
        if (sender == null) {
            throw new ChallengeDeliveryUnavailableException(
                    "验证码发送通道未接通（短信/邮件服务商未配置），无法完成归属验证");
        }
        sender.send(new OwnershipChallengeSender.ChallengeDelivery(
                provider.name(), subject, code, expiresAt));

        log.info("guest ownership challenge issued accountId={} provider={} subject={} challengeId={}",
                accountId, provider, mask(subject), challengeId);
        return new ChallengeIssued(challengeId, provider.name(), mask(subject), expiresAt);
    }

    /**
     * 提交验证码完成绑定。
     *
     * @throws NotFoundException 挑战不存在（含"不是你的挑战"——不泄露别的挑战存在）
     * @throws ConflictException 挑战已失效（过期/超次/已消费）或目标已被占用
     * @throws IllegalArgumentException 验证码不正确（会计入尝试次数）
     */
    @Transactional
    public List<GuestCredentialView> confirm(UUID accountId, UUID challengeId, String code) {
        OwnershipChallenge challenge = guestMapper.findChallenge(challengeId)
                .orElseThrow(() -> new NotFoundException("归属挑战不存在"));

        // 归属校验在**状态检查之前**：别人的挑战对你而言就是"不存在"，
        // 不存在/过期/已消费的文案差异对挑战所有者才有意义。
        if (!challenge.accountId().equals(accountId)) {
            throw new NotFoundException("归属挑战不存在");
        }
        if (challenge.isConsumed()) {
            throw new ConflictException("该验证码已使用，请重新发起归属验证");
        }
        if (challenge.isExhausted()) {
            throw new ConflictException("错误次数已达上限，请重新发起归属验证");
        }
        if (challenge.isExpiredAt(OffsetDateTime.now(clock))) {
            throw new ConflictException("验证码已过期，请重新发起归属验证");
        }

        if (!constantTimeEquals(code == null ? "" : code.trim(), challenge.codeHash())) {
            // 独立事务计数：本方法的事务会因下面的异常回滚，
            // 同事务计数会被一起回滚——防线就没了（见 ChallengeAttemptRecorder 注释）
            attemptRecorder.recordFailure(challengeId);
            throw new IllegalArgumentException("验证码不正确");
        }

        // 原子消费：并发下第二个 confirm 拿到 0 行 → 409，同一码不会绑两次
        int consumed = guestMapper.consumeChallenge(challengeId,
                OwnershipChallenge.MAX_ATTEMPTS, OffsetDateTime.now(clock));
        if (consumed == 0) {
            throw new ConflictException("该验证码已使用，请重新发起归属验证");
        }

        // 绑定。占用冲突由唯一索引兜底（挑战创建后别人先绑了同一手机号的竞态）
        GuestProvider provider = GuestProvider.valueOf(challenge.provider());
        try {
            guestMapper.insertCredential(UUID.randomUUID(), accountId,
                    provider.name(), challenge.subject(), null, OffsetDateTime.now(clock),
                    OffsetDateTime.now(clock));
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ConflictException("该登录方式已被其它账户绑定");
        }

        log.info("guest credential bound accountId={} provider={} via challenge {}",
                accountId, provider, challengeId);
        return credentials(accountId);
    }

    /** 我的凭证列表（本人可见完整标识；管理端看到的是掩码，另一条路径）。 */
    @Transactional(readOnly = true)
    public List<GuestCredentialView> credentials(UUID accountId) {
        return guestMapper.findCredentialsOfAccount(accountId);
    }

    /**
     * 解绑一个凭证。
     *
     * <p><b>自保护：不能解绑最后一个凭证</b>。解绑完没有任何登录方式，
     * 这个账户就再也进不来，而住客没有"平台帮恢复"的路（A-05 未建）。
     * 与员工侧"不能停用自己"同源：自毁动作要么禁止，要么有恢复通道。
     */
    @Transactional
    public List<GuestCredentialView> unbind(UUID accountId, String providerName, String rawSubject) {
        GuestProvider provider;
        try {
            provider = GuestProvider.valueOf(providerName);
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException("未知的登录方式：" + providerName);
        }
        String subject = provider.normalize(rawSubject);

        long count = guestMapper.countCredentials(accountId);
        if (count <= 1) {
            throw new ConflictException("至少保留一个登录方式，否则该账户将无法登录；"
                    + "如需更换请先绑定新的方式");
        }

        int deleted = guestMapper.deleteCredential(accountId, provider.name(), subject);
        if (deleted == 0) {
            throw new NotFoundException("该登录方式未绑定在此账户上");
        }
        log.info("guest credential unbound accountId={} provider={}", accountId, provider);
        return credentials(accountId);
    }

    /**
     * 绑定用户名（无需挑战：密码即归属证明，见 ADR-019 §2.9）。
     * 与其它绑定一致只看能力不看登录开关——绑完能不能用由登录时的开关决定。
     */
    @Transactional
    public List<GuestCredentialView> bindUsername(UUID accountId, String username, String password) {
        accountService.bindUsername(accountId, username, password);
        return credentials(accountId);
    }

    // ==================== 内部 ====================

    private void assertSubjectFree(GuestProvider provider, String subject) {
        guestMapper.findAccountIdByCredential(provider.name(), subject).ifPresent(other -> {
            throw new ConflictException("该登录方式已被其它账户绑定");
        });
    }

    /**
     * 验证码比对。
     *
     * <p>库里存的是哈希，这里比对"提交码的哈希"与"创建时的哈希"。
     * 用常量时间比较：字符串逐字符短路会让攻击者按位置逐位猜码
     * （每次请求只暴露第一位对不对），6 位码的爆破成本从 10^6 降到 60。
     */
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

    /**
     * 日志与响应里的标识掩码：手机号前 3 后 4（尾号是用户辨认的主要依据），
     * 邮箱留首字符与域名。
     */
    private static String mask(String subject) {
        if (subject.contains("@")) {
            int at = subject.indexOf('@');
            return subject.charAt(0) + "***" + subject.substring(at);
        }
        return subject.length() <= 7
                ? "***"
                : subject.substring(0, 3) + "****" + subject.substring(subject.length() - 4);
    }

    /** 挑战发起结果。**不含验证码**——码只经发送通道走。 */
    public record ChallengeIssued(
            UUID challengeId,
            String provider,
            String subjectMasked,
            OffsetDateTime expiresAt
    ) {
    }
}
