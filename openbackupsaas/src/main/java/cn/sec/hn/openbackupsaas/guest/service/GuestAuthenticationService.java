package cn.sec.hn.openbackupsaas.guest.service;

import cn.sec.hn.openbackupsaas.guest.domain.GuestAccount;
import cn.sec.hn.openbackupsaas.guest.domain.GuestAccountStatus;
import cn.sec.hn.openbackupsaas.guest.domain.GuestSession;
import cn.sec.hn.openbackupsaas.guest.domain.GuestSessionView;
import cn.sec.hn.openbackupsaas.guest.domain.GuestAccountStatus;
import cn.sec.hn.openbackupsaas.guest.domain.GuestProvider;
import cn.sec.hn.openbackupsaas.guest.mapper.GuestMapper;
import cn.sec.hn.openbackupsaas.guest.security.GuestPrincipal;
import cn.sec.hn.openbackupsaas.guest.security.GuestSessionTokens;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import cn.sec.hn.openbackupsaas.shared.error.UnauthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 住客会话的签发与解析（ADR-019 §2.3）。
 *
 * <p><b>与员工认证服务没有任何共用代码路径</b>：不同的表、不同的令牌派生域、
 * 不同的主体类型、不同的过滤器链。共用一次"顺手"，就会留下一条跨平面的通路。
 *
 * <p>本轮**不开放登录 HTTP 接口**：住客登录靠短信/邮箱验证码或 OAuth 回调，
 * 这些外部通道还没接（ADR-019 §6）。但会话机制本身是完整的、可测的——
 * 将来接验证码时只需要在"凭证 verified"之后调 {@link #issueSession}，
 * 不需要动认证与授权这一段。
 */
@Service
public class GuestAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(GuestAuthenticationService.class);

    private final GuestMapper guestMapper;
    private final GuestAccountService guestAccountService;
    private final GuestMethodAvailability methodAvailability;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Duration sessionTtl;

    public GuestAuthenticationService(GuestMapper guestMapper,
                                     GuestAccountService guestAccountService,
                                     GuestMethodAvailability methodAvailability,
                                     org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                                     Clock clock,
                                     @Value("${pms.guest.session-ttl:PT24H}") Duration sessionTtl) {
        if (sessionTtl.isNegative() || sessionTtl.isZero()) {
            // 配置成 0 或负数会让每个请求都立刻失效，表现为"登录成功但什么都打不开"，
            // 与其让人排查这种症状，不如启动时就拒绝。
            throw new IllegalArgumentException("住客会话有效期必须为正数，当前=" + sessionTtl);
        }
        this.guestMapper = guestMapper;
        this.guestAccountService = guestAccountService;
        this.methodAvailability = methodAvailability;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.sessionTtl = sessionTtl;
    }

    /**
     * 为一住客签发会话，返回**明文令牌**（只此一次，不落库、不写日志）。
     *
     * <p>前置条件由调用方保证"这个凭证已被证明属于该账户"——验证码校验通过、
     * 或 OAuth 回调验签通过。本方法只负责签发，不判断"该不该让他登录"，
     * 否则就会长出一个"忘记校验凭证"的入口。
     */
    @Transactional
    public IssuedSession issueSession(UUID accountId) {
        GuestAccount account = guestMapper.findAccount(accountId)
                .orElseThrow(() -> new NotFoundException("住客账户不存在：" + accountId));
        if (account.status() != GuestAccountStatus.ACTIVE) {
            // 已关闭的账户不该还能登录：这里用 404 而不是 403，
            // 避免把"这个账户存在但被关了"当成可探测的信息。
            throw new NotFoundException("住客账户不存在：" + accountId);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime expiresAt = now.plus(sessionTtl);
        String token = GuestSessionTokens.generate();
        GuestSession session = GuestSession.issue(UUID.randomUUID(), accountId,
                GuestSessionTokens.hash(token), now, expiresAt);
        guestMapper.insertSession(session);
        log.info("guest session issued accountId={} sessionId={} fingerprint={}",
                accountId, session.id(), GuestSessionTokens.fingerprint(token));
        return new IssuedSession(token, expiresAt);
    }

    /**
     * 解析住客令牌。无效/过期/已撤销/账户已关闭 一律返回 empty——
     * 调用方（过滤器）不需要区分这些情况，区分只会多出可探测的差异。
     */
    public Optional<GuestPrincipal> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return guestMapper.findUsableSession(GuestSessionTokens.hash(rawToken))
                .map(view -> {
                    // 实名状态每请求现读：人工审核通过后，下一次请求就解开限制。
                    var status = guestAccountService.verificationStatus(view.accountId());
                    return new GuestPrincipal(view.sessionId(), view.accountId(),
                            view.displayName(), status, view.expiresAt());
                });
    }

    /**
     * 用户名 + 密码登录（ADR-019 §2.9）。
     *
     * <p>失败统一为一句话（"用户名或密码不正确"），**不区分**用户名不存在、
     * 密码错误、账户已关闭——三个分支对攻击者的价值不同（是否存在这个用户名），
     * 统一之后登录接口不构成账号枚举器。这与员工登录、平台登录是同一条纪律。
     *
     * <p>方式可用性先于凭据校验：方式被关掉时直接 403"未开放"，
     * 不会再走口令比对（那种情况下比对结果毫无意义）。
     */
    @Transactional
    public AuthenticatedLogin loginWithUsernamePassword(String rawUsername, String rawPassword) {
        requireMethodAvailable(GuestProvider.USERNAME);
        String username = GuestProvider.USERNAME.normalize(rawUsername);

        var credential = guestMapper.findUsernameCredential(username)
                .orElseThrow(() -> new UnauthenticatedException("用户名或密码不正确"));
        if (!passwordEncoder.matches(rawPassword, credential.secretHash())) {
            throw new UnauthenticatedException("用户名或密码不正确");
        }

        // 比对通过后才读账户：账户不存在/已关闭与"密码错"同文案，
        // 不给出"这个用户名存在但被关了"这类可探测差异。
        guestMapper.findAccount(credential.accountId())
                .filter(account -> account.status() == GuestAccountStatus.ACTIVE)
                .orElseThrow(() -> new UnauthenticatedException("用户名或密码不正确"));

        IssuedSession issued = issueSession(credential.accountId());
        return new AuthenticatedLogin(issued.token(), issued.expiresAt(), credential.accountId());
    }

    /**
     * 方式可用性 = 实现状态（代码）∧ 策略开关（控制台），见 ADR-019 §2.9。
     *
     * <p>策略行缺失按禁用处理：种子覆盖了全部方式，缺行只可能是有人误删，
     * 那时宁可让人来问"为什么登录不了"，也不要在缺行状态下放行。
     */
    private void requireMethodAvailable(GuestProvider provider) {
        // 判定规则唯一实现点见 GuestMethodAvailability：登录与凭证绑定必须同一条规则
        methodAvailability.requireAvailable(provider);
    }

    /**
     * 撤销**单个**会话（登出用）。
     *
     * <p>"退出登录"是这台设备的事；"踢掉所有设备"是账户安全动作，
     * 两件事语义不同，所以有两个方法。混用一个会让"换台手机登出"
     * 把旧手机上的会话一起杀掉——用户会觉得莫名其妙。
     */
    @Transactional
    public void revokeSession(java.util.UUID sessionId, String reason) {
        guestMapper.revokeSession(sessionId, reason, OffsetDateTime.now(clock));
    }

    /** 撤销某住客的全部会话（关闭账户、注销账户时用）。 */
    @Transactional
    public int revokeAllSessions(UUID accountId, String reason) {
        int revoked = guestMapper.revokeAllSessionsOfAccount(accountId, reason,
                OffsetDateTime.now(clock));
        log.info("guest sessions revoked accountId={} count={} reason={}", accountId, revoked, reason);
        return revoked;
    }

    /** 签发结果：明文令牌 + 过期时间。令牌只出现在响应体里。 */
    public record IssuedSession(String token, OffsetDateTime expiresAt) {
    }

    /** 登录结果：会话 + 账户（供响应组装实名快照）。 */
    public record AuthenticatedLogin(String token, OffsetDateTime expiresAt,
                                     UUID accountId) {
    }

    /**
     * 注册/登录响应所需的账户快照（显示名 + 实名状态）。
     *
     * <p>放在服务层而不是控制器里现拼：让"响应里的实名状态与库同源"
     * 只有一条实现路径，注册与登录两条入口不会各自拼出微妙不同的版本。
     */
    @Transactional(readOnly = true)
    public GuestMeSnapshot snapshotOf(UUID accountId) {
        GuestAccount account = guestMapper.findAccount(accountId)
                .orElseThrow(() -> new NotFoundException("住客账户不存在：" + accountId));
        return new GuestMeSnapshot(account.displayName(),
                guestAccountService.verificationStatus(accountId));
    }

    public record GuestMeSnapshot(String displayName,
                                  cn.sec.hn.openbackupsaas.guest.domain.GuestVerificationStatus status) {
    }
}
