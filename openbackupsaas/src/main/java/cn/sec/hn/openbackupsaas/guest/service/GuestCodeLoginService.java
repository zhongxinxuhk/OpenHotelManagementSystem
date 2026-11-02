package cn.sec.hn.openbackupsaas.guest.service;

import cn.sec.hn.openbackupsaas.guest.domain.GuestProvider;
import cn.sec.hn.openbackupsaas.guest.domain.LoginChallenge;
import cn.sec.hn.openbackupsaas.guest.mapper.GuestMapper;
import cn.sec.hn.openbackupsaas.guest.ownership.ChallengeSenderRouter;
import cn.sec.hn.openbackupsaas.guest.ownership.OwnershipChallengeSender;
import cn.sec.hn.openbackupsaas.shared.error.ChallengeDeliveryUnavailableException;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.shared.error.LoginMethodUnavailableException;
import cn.sec.hn.openbackupsaas.shared.error.UnauthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 验证码登录（R-20261102-07，ADR-019 §2.10 续）。
 *
 * <p>EMAIL+验证码登录的**消费侧**：投递通道与挑战机制（防爆破三件套）在
 * R-20261102-01/33 已就绪，本服务把两者接成登录方式。两步：
 * <ol>
 *   <li>{@link #requestChallenge(String)}：匿名发起——向目标邮箱发 6 位验证码；</li>
 *   <li>{@link #loginWithCode(UUID, String)}：码校验通过后按 (EMAIL, subject)
 *       定位凭证与账户，签发会话。</li>
 * </ol>
 *
 * <h2>与绑定挑战（{@link GuestBindingService}）分表分服务的原因</h2>
 * <ul>
 *   <li><b>语义不同</b>：绑定挑战是"归属验证"（发起人已登录，挑战绑定 account_id，
 *       confirm 校验归属防接管）；登录挑战是"身份证明"（匿名发起，account_id
 *       无法归属，凭证定位发生在码校验之后）。两者的表约束一严一松，
 *       合一张表必然互相放松。</li>
 *   <li><b>可用性不该联动</b>：登录挑战必须过 {@code requireAvailable(EMAIL)}
 *       （实现 ∧ 登录开关）；绑定挑战只看通道能力。混用会让"运营临时关停
 *       EMAIL 登录"连带堵死"绑定邮箱"——那是两个动作，可用性本来就不该联动。</li>
 * </ul>
 *
 * <h2>枚举防护（需求方已确认口径：照发码 + 统一文案）</h2>
 * <ul>
 *   <li>发起挑战时**不查邮箱是否已注册**：未注册邮箱也照发验证码，
 *       响应形态（challengeId/掩码/过期时间）与已注册完全一致；</li>
 *   <li>提交码时"邮箱未注册"、"码不正确"、"账户已关闭"统一为同一句
 *       "验证码或邮箱不正确"（401）——登录接口不构成邮箱枚举器，
 *       与用户名登录的账号枚举防护同一条纪律。</li>
 * </ul>
 */
@Service
public class GuestCodeLoginService {

    private static final Logger log = LoggerFactory.getLogger(GuestCodeLoginService.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final GuestMapper guestMapper;
    private final GuestAuthenticationService authenticationService;
    private final GuestMethodAvailability methodAvailability;
    private final ChallengeSenderRouter senderRouter;
    private final LoginChallengeAttemptRecorder attemptRecorder;
    private final Clock clock;

    public GuestCodeLoginService(GuestMapper guestMapper,
                                 GuestAuthenticationService authenticationService,
                                 GuestMethodAvailability methodAvailability,
                                 ChallengeSenderRouter senderRouter,
                                 LoginChallengeAttemptRecorder attemptRecorder,
                                 Clock clock) {
        this.guestMapper = guestMapper;
        this.authenticationService = authenticationService;
        this.methodAvailability = methodAvailability;
        this.senderRouter = senderRouter;
        this.attemptRecorder = attemptRecorder;
        this.clock = clock;
    }

    /**
     * 匿名发起登录挑战：向目标邮箱发 6 位验证码。
     *
     * <p>**枚举防护**：不查邮箱是否注册——未注册邮箱照发码，响应形态与
     * 已注册一致。邮件会真实投递（未注册用户收到一封无法完成登录的验证码，
     * 文案由邮件模板负责提示），这比"未注册时响应不同形态"安全：
     * 后者让任何人都能用本接口探测某邮箱是否已注册。
     *
     * @throws LoginMethodUnavailableException EMAIL 登录被关停（实现 ∧ 开关）
     * @throws ChallengeDeliveryUnavailableException 发送通道未接通（503）
     */
    @Transactional
    public ChallengeIssued requestChallenge(String rawEmail) {
        // 登录挑战必须过登录开关：与绑定挑战"只看通道能力"刻意不同。
        methodAvailability.requireAvailable(GuestProvider.EMAIL);

        String email = GuestProvider.EMAIL.normalize(rawEmail);

        OffsetDateTime now = OffsetDateTime.now(clock);
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        UUID challengeId = UUID.randomUUID();
        OffsetDateTime expiresAt = now.plusMinutes(LoginChallenge.TTL_MINUTES);

        // account_id 不写：登录前无法归属（表刻意无外键）。
        guestMapper.insertLoginChallenge(challengeId, GuestProvider.EMAIL.name(),
                email, sha256(code), expiresAt, now);

        // 路由器集中负责"方式→实现"分发与"未接通→503"；
        // 发送失败时事务回滚，不留下"声称已发码"的孤儿挑战。
        senderRouter.send(new OwnershipChallengeSender.ChallengeDelivery(
                GuestProvider.EMAIL.name(), email, code, expiresAt));

        log.info("guest login challenge issued provider=EMAIL subject={} challengeId={}",
                mask(email), challengeId);
        return new ChallengeIssued(challengeId, mask(email), expiresAt);
    }

    /**
     * 用验证码完成登录。
     *
     * <p>防爆破与绑定挑战同构：码错计入尝试次数（5 次上限，独立事务——
     * 本方法的回滚不能把计数一起卷走）；一次性消费防重放。
     *
     * <p>**统一文案**：邮箱未注册、码不正确、账户已关闭，一律
     * "验证码或邮箱不正确"——分支差异只对挑战所有者有意义，对接口调用方
     * 是可探测信息。
     *
     * @throws UnauthenticatedException 码错 / 邮箱未注册 / 账户关闭（同一句）
     * @throws ConflictException 挑战已消费 / 超次 / 过期（需重新发起）
     */
    @Transactional
    public GuestAuthenticationService.AuthenticatedLogin loginWithCode(UUID challengeId, String code) {
        // 方式开关在 confirm 也检查：运营在挑战发出后关停 EMAIL 登录，
        // 已发出的挑战不应继续完成登录（挑战生命周期只有 10 分钟，代价可接受）。
        methodAvailability.requireAvailable(GuestProvider.EMAIL);

        LoginChallenge challenge = guestMapper.findLoginChallenge(challengeId)
                .orElseThrow(() -> new ConflictException("验证码已失效，请重新发起登录"));
        if (challenge.isConsumed()) {
            throw new ConflictException("该验证码已使用，请重新发起登录");
        }
        if (challenge.isExhausted()) {
            throw new ConflictException("错误次数已达上限，请重新发起登录");
        }
        if (challenge.isExpiredAt(OffsetDateTime.now(clock))) {
            throw new ConflictException("验证码已过期，请重新发起登录");
        }

        if (!constantTimeEquals(code == null ? "" : code.trim(), challenge.codeHash())) {
            // 独立事务计数：本事务即将回滚，同事务计数会被卷走（见 recorder 注释）
            attemptRecorder.recordFailure(challengeId);
            throw new UnauthenticatedException("验证码或邮箱不正确");
        }

        // 原子消费：并发下第二个提交拿到 0 行 → 409，同一码不会登录两次
        int consumed = guestMapper.consumeLoginChallenge(challengeId);
        if (consumed == 0) {
            throw new ConflictException("该验证码已使用，请重新发起登录");
        }

        // 码校验通过后定位凭证。未注册 / 账户关闭与"码错"同文案：
        // 这三个分支对调用方的差异是可探测信息，统一之后接口不构成枚举器。
        UUID accountId = guestMapper
                .findActiveAccountIdByCredential(GuestProvider.EMAIL.name(), challenge.subject())
                .orElseThrow(() -> {
                    log.info("guest code login failed: no active credential subject={}",
                            mask(challenge.subject()));
                    return new UnauthenticatedException("验证码或邮箱不正确");
                });

        GuestAuthenticationService.IssuedSession issued = authenticationService.issueSession(accountId);
        log.info("guest logged in via EMAIL code subject={}", mask(challenge.subject()));
        return new GuestAuthenticationService.AuthenticatedLogin(
                issued.token(), issued.expiresAt(), accountId);
    }

    // ==================== 内部 ====================

    /**
     * 验证码比对：常量时间。逐字符短路会让攻击者按位置逐位猜码
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

    /** 日志与响应里的邮箱掩码：首字符 + *** + @域名（与绑定挑战同规则）。 */
    private static String mask(String subject) {
        int at = subject.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return subject.charAt(0) + "***" + subject.substring(at);
    }

    /** 登录挑战发起结果。**不含验证码**——码只经发送通道走。 */
    public record ChallengeIssued(
            UUID challengeId,
            String subjectMasked,
            OffsetDateTime expiresAt
    ) {
    }
}
