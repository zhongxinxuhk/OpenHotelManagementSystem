package cn.sec.hn.openbackupsaas.identity.service;

import cn.sec.hn.openbackupsaas.identity.domain.AppUser;
import cn.sec.hn.openbackupsaas.identity.domain.SessionView;
import cn.sec.hn.openbackupsaas.identity.domain.UserSession;
import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.shared.error.UnauthenticatedException;
import cn.sec.hn.openbackupsaas.shared.security.PmsPrincipal;
import cn.sec.hn.openbackupsaas.shared.security.SessionTokens;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.mapper.OrganizationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 认证与会话（S1-03）。
 *
 * <p>核心约束：
 * <ul>
 *   <li><b>失败一律同一文案。</b>租户不存在、账号不存在、密码错、账号停用、租户停用，
 *       对外都是同一个 401 与同一句话，避免登录接口变成账号/租户枚举器；
 *       真实原因只进服务端日志（不含密码）。</li>
 *   <li><b>身份每次请求现读。</b>权限点与门店范围不写进令牌、不做缓存快照，
 *       因此撤权、调岗、停门店在下一个请求就生效，不需要失效广播。</li>
 *   <li><b>明文令牌只在登录响应出现一次。</b>库内只存 SHA-256，日志只打 12 位指纹。</li>
 * </ul>
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    /** 对外唯一的失败文案：不区分租户、账号与密码。 */
    private static final String GENERIC_FAILURE = "登录失败：凭据无效或账号不可用";

    /**
     * 账号不存在时用于"陪跑"一次 BCrypt 比对的固定哈希（"password" 的 BCrypt 值）。
     * 目的是让"账号不存在"与"密码错误"的响应耗时接近，减少通过时间差枚举账号的可能。
     */
    private static final String DUMMY_BCRYPT = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /** 口令长度下限 12：达到 NIST SP 800-63B 对单因素口令的最低建议长度。 */
    private static final int PASSWORD_MIN_LENGTH = 12;
    private static final int PASSWORD_MAX_LENGTH = 128;

    private final IdentityMapper identityMapper;
    private final OrganizationMapper organizationMapper;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Duration sessionTtl;

    public AuthenticationService(IdentityMapper identityMapper,
                                 OrganizationMapper organizationMapper,
                                 PasswordEncoder passwordEncoder,
                                 Clock clock,
                                 @Value("${pms.security.session-ttl:PT8H}") Duration sessionTtl) {
        if (sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("会话有效期必须为正时长");
        }
        this.identityMapper = identityMapper;
        this.organizationMapper = organizationMapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.sessionTtl = sessionTtl;
    }

    /**
     * 登录：校验租户 → 账号 → 口令，成功后落一条服务端会话。
     *
     * <p>登录接口是唯一允许从请求体接受 {@code tenantCode} 的地方：这里是建立身份的入口，
     * 之后所有请求的租户只能来自会话（B-06）。
     */
    @Transactional
    public LoginResult login(String tenantCode, String username, String rawPassword) {
        String code = normalizeTenantCode(tenantCode);
        String login = normalizeUsername(username);

        Tenant tenant = organizationMapper.findTenantByCode(code).orElse(null);
        if (tenant == null || !tenant.status().allowsWrite()) {
            log.info("login rejected: tenant unavailable code={}", code);
            throw new UnauthenticatedException(GENERIC_FAILURE);
        }

        AppUser user = identityMapper.findUserByUsername(tenant.id(), login).orElse(null);
        if (user == null) {
            // 陪跑一次哈希，避免"账号不存在"明显更快
            passwordEncoder.matches(rawPassword, DUMMY_BCRYPT);
            log.info("login rejected: unknown user tenantId={} username={}", tenant.id(), login);
            throw new UnauthenticatedException(GENERIC_FAILURE);
        }
        if (!user.status().canLogin()) {
            log.info("login rejected: user disabled tenantId={} userId={}", tenant.id(), user.id());
            throw new UnauthenticatedException(GENERIC_FAILURE);
        }
        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            log.info("login rejected: bad password tenantId={} userId={}", tenant.id(), user.id());
            throw new UnauthenticatedException(GENERIC_FAILURE);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime expiresAt = now.plus(sessionTtl);
        String token = SessionTokens.generate();
        UserSession session = UserSession.issue(UUID.randomUUID(), tenant.id(), user.id(),
                SessionTokens.hash(token), now, expiresAt);
        identityMapper.insertSession(session);
        identityMapper.updateLastLogin(tenant.id(), user.id(), now);

        PmsPrincipal principal = buildPrincipal(session, user);
        log.info("login succeeded tenantId={} userId={} sessionId={} token={}",
                tenant.id(), user.id(), session.id(), SessionTokens.fingerprint(token));
        return new LoginResult(token, expiresAt, principal);
    }

    /**
     * 每个请求解析令牌。返回空表示"没有可用会话"，由安全链按未认证处理（401），
     * 这里不区分"令牌不存在/已撤销/已过期/账号停用"，避免把失效原因回显给客户端。
     */
    @Transactional(readOnly = true)
    public Optional<PmsPrincipal> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return identityMapper.findUsableSession(SessionTokens.hash(rawToken))
                .map(view -> {
                    Set<String> permissions = Set.copyOf(
                            identityMapper.findPermissionCodes(view.tenantId(), view.userId()));
                    Set<UUID> hotelIds = Set.copyOf(
                            identityMapper.findHotelScopeIds(view.tenantId(), view.userId()));
                    return new PmsPrincipal(view.sessionId(), view.tenantId(), view.userId(),
                            view.username(), view.displayName(), view.expiresAt(), hotelIds, permissions);
                });
    }

    /** 登出：只撤销当前会话，其他设备上的会话不受影响。 */
    @Transactional
    public void logout(PmsPrincipal principal) {
        int revoked = identityMapper.revokeSession(principal.tenantId(), principal.sessionId(),
                OffsetDateTime.now(clock), "LOGOUT");
        log.info("logout tenantId={} userId={} sessionId={} revoked={}",
                principal.tenantId(), principal.userId(), principal.sessionId(), revoked);
    }

    /**
     * 改密：校验原口令 → 换新口令 → 回收**其他**会话。
     *
     * <p>保留当前会话是有意的：改密的人不该被自己踢下线；其余会话立即失效，
     * 这就是 B-04「撤权后旧会话失效」在口令场景下的落地约定。
     */
    @Transactional
    public void changePassword(PmsPrincipal principal, String currentRawPassword, String newRawPassword) {
        AppUser user = identityMapper.findUser(principal.tenantId(), principal.userId())
                .orElseThrow(() -> new UnauthenticatedException(GENERIC_FAILURE));

        if (!passwordEncoder.matches(currentRawPassword, user.passwordHash())) {
            log.info("change password rejected: bad current password tenantId={} userId={}",
                    principal.tenantId(), principal.userId());
            throw new UnauthenticatedException(GENERIC_FAILURE);
        }
        requireAcceptablePassword(newRawPassword, user.username());
        if (passwordEncoder.matches(newRawPassword, user.passwordHash())) {
            throw new IllegalArgumentException("新口令不能与当前口令相同");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        identityMapper.updatePasswordHash(principal.tenantId(), principal.userId(),
                passwordEncoder.encode(newRawPassword), now);
        int revoked = identityMapper.revokeUserSessions(principal.tenantId(), principal.userId(), now,
                "PASSWORD_CHANGED", principal.sessionId());
        log.info("password changed tenantId={} userId={} otherSessionsRevoked={}",
                principal.tenantId(), principal.userId(), revoked);
    }

    private PmsPrincipal buildPrincipal(UserSession session, AppUser user) {
        Set<String> permissions = Set.copyOf(
                identityMapper.findPermissionCodes(session.tenantId(), session.userId()));
        Set<UUID> hotelIds = Set.copyOf(
                identityMapper.findHotelScopeIds(session.tenantId(), session.userId()));
        return new PmsPrincipal(session.id(), session.tenantId(), session.userId(),
                user.username(), user.displayName(), session.expiresAt(), hotelIds, permissions);
    }

    /** 口令策略在此收口；长度之外的复杂度规则（禁用弱口令字典）待 S6-05 安全验收补充。 */
    private static void requireAcceptablePassword(String rawPassword, String username) {
        if (rawPassword == null || rawPassword.length() < PASSWORD_MIN_LENGTH) {
            throw new IllegalArgumentException("新口令长度不足 " + PASSWORD_MIN_LENGTH + " 位");
        }
        if (rawPassword.length() > PASSWORD_MAX_LENGTH) {
            throw new IllegalArgumentException("新口令长度超过 " + PASSWORD_MAX_LENGTH + " 位");
        }
        if (rawPassword.toLowerCase(java.util.Locale.ROOT).contains(username)) {
            throw new IllegalArgumentException("新口令不能包含登录名");
        }
    }

    private static String normalizeTenantCode(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new UnauthenticatedException(GENERIC_FAILURE);
        }
        return tenantCode.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new UnauthenticatedException(GENERIC_FAILURE);
        }
        return username.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 登录结果。{@code token} 是明文令牌，只允许写进登录响应体；
     * 它的 {@code toString} 被覆写掉，防止被顺手打进日志。
     */
    public record LoginResult(String token, OffsetDateTime expiresAt, PmsPrincipal principal) {

        @Override
        public String toString() {
            return "LoginResult[principal=" + principal.username() + ", expiresAt=" + expiresAt + "]";
        }
    }

    /** 供测试与后续 S3-02 复用的只读投影。 */
    @Transactional(readOnly = true)
    public List<UUID> hotelScopesOf(UUID tenantId, UUID userId) {
        return identityMapper.findHotelScopeIds(tenantId, userId);
    }

    /** 供测试与后续 S2-01 Worker 复用：按 token 取会话视图（不含权限）。 */
    @Transactional(readOnly = true)
    public Optional<SessionView> sessionFor(String rawToken) {
        return identityMapper.findUsableSession(SessionTokens.hash(rawToken));
    }
}
