package cn.sec.hn.openbackupsaas.platform.service;

import cn.sec.hn.openbackupsaas.platform.domain.PlatformSession;
import cn.sec.hn.openbackupsaas.platform.domain.PlatformUser;
import cn.sec.hn.openbackupsaas.platform.mapper.PlatformMapper;
import cn.sec.hn.openbackupsaas.identity.domain.PasswordPolicy;
import cn.sec.hn.openbackupsaas.platform.security.PlatformPrincipal;
import cn.sec.hn.openbackupsaas.platform.security.PlatformSessionTokens;
import cn.sec.hn.openbackupsaas.shared.error.UnauthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 平台登录与令牌解析（ADR-019 §2.3）。
 *
 * <p>与员工认证的差别：
 * <ul>
 *   <li>查 {@code platform_user}，不是 {@code app_user}——没有租户编码参与定位，
 *       用户名全局唯一；</li>
 *   <li>动作集合来自 {@code platform_user_action}，与租户权限点字典不同表；</li>
 *   <li>登录失败**一律**同一句话，不区分"账号不存在"与"口令错"。</li>
 * </ul>
 *
 * <p>平台会话默认 4 小时（短于员工的 8 小时）：平台动作影响面是全局的
 * （改核验渠道地址、审实名资料），长会话在这里比在商家端危险得多。
 */
@Service
public class PlatformAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAuthenticationService.class);

    private final PlatformMapper platformMapper;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Duration sessionTtl;

    public PlatformAuthenticationService(PlatformMapper platformMapper,
                                         PasswordEncoder passwordEncoder,
                                         Clock clock,
                                         @Value("${pms.platform.session-ttl:PT4H}") Duration sessionTtl) {
        if (sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("平台会话有效期必须为正数，当前=" + sessionTtl);
        }
        this.platformMapper = platformMapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.sessionTtl = sessionTtl;
    }

    /**
     * 平台登录。
     *
     * <p>失败统一抛 {@link UnauthenticatedException}，且**先查口令再判状态**会泄露
     * "账号存在但被停用"（响应时间差 + 不同分支）。这里的顺序是：查不到 → 立刻失败；
     * 查到了 → 口令比对 → 状态判断，两条失败路径的返回文案完全相同。
     */
    @Transactional
    public IssuedPlatformSession login(String username, String rawPassword) {
        PlatformUser user = platformMapper.findUserByUsername(username)
                .orElseThrow(() -> new UnauthenticatedException("凭据无效"));

        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            log.warn("platform login failed (bad password) username={}", username);
            throw new UnauthenticatedException("凭据无效");
        }
        if (!user.isActive()) {
            log.warn("platform login failed (disabled) username={}", username);
            throw new UnauthenticatedException("凭据无效");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime expiresAt = now.plus(sessionTtl);
        String token = PlatformSessionTokens.generate();
        PlatformSession session = PlatformSession.issue(UUID.randomUUID(), user.id(),
                PlatformSessionTokens.hash(token), now, expiresAt);
        platformMapper.insertSession(session);
        platformMapper.updateLastLogin(user.id(), now);

        Set<String> actions = Set.copyOf(platformMapper.findActions(user.id()));
        PlatformPrincipal principal = new PlatformPrincipal(session.id(), user.id(),
                user.username(), user.displayName(), user.mustChangePassword(),
                actions, expiresAt);

        log.info("platform login ok userId={} username={} actions={} fingerprint={}",
                user.id(), user.username(), actions.size(), PlatformSessionTokens.fingerprint(token));
        return new IssuedPlatformSession(token, expiresAt, principal);
    }

    /**
     * 解析平台令牌。
     *
     * <p>动作集合每请求现读：撤销一个动作后，下一次请求就少一项，不需要等会话过期。
     * 这对"临时授权某人审一批实名"这种场景是必需的。
     */
    public Optional<PlatformPrincipal> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return platformMapper.findUsableSession(PlatformSessionTokens.hash(rawToken))
                .map(view -> {
                    Set<String> actions = Set.copyOf(platformMapper.findActions(view.userId()));
                    return new PlatformPrincipal(view.sessionId(), view.userId(),
                            view.username(), view.displayName(), view.mustChangePassword(),
                            actions, view.expiresAt());
                });
    }

    /** 登出：撤销当前会话。 */
    @Transactional
    public void logout(PlatformPrincipal principal) {
        platformMapper.revokeSession(principal.sessionId(), "LOGOUT", OffsetDateTime.now(clock));
    }

    /**
     * 平台改密。成功后清掉"必须改密"标记，并撤销**其它**所有会话。
     *
     * <p>当前口令错了返回 400 而不是 401：调用方已经带着有效会话，
     * 这是"输入错了"而不是"身份没了"。两种错误混在一个码里，
     * 前端会把人踢回登录页，但那个人其实登录着。
     */
    @Transactional
    public void changePassword(PlatformPrincipal principal,
                               String currentPassword,
                               String newPassword) {
        PlatformUser user = platformMapper.findUserByUsername(principal.username())
                .orElseThrow(() -> new UnauthenticatedException("凭据无效"));
        if (!passwordEncoder.matches(currentPassword, user.passwordHash())) {
            throw new IllegalArgumentException("当前口令不正确");
        }
        PasswordPolicy.requireAcceptable(newPassword, user.username());

        OffsetDateTime now = OffsetDateTime.now(clock);
        platformMapper.updatePasswordHash(user.id(), passwordEncoder.encode(newPassword), now);
        int revoked = platformMapper.revokeOtherSessions(user.id(), principal.sessionId(),
                "PASSWORD_CHANGED", now);
        log.info("platform password changed userId={} otherSessionsRevoked={}", user.id(), revoked);
    }

    /** 签发结果：明文令牌只在这里出现一次。 */
    public record IssuedPlatformSession(String token, OffsetDateTime expiresAt,
                                        PlatformPrincipal principal) {
    }
}
