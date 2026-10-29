package cn.sec.hn.openbackupsaas.identity;

import cn.sec.hn.openbackupsaas.identity.service.AuthenticationService;
import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.shared.error.UnauthenticatedException;
import cn.sec.hn.openbackupsaas.shared.security.Authz;
import cn.sec.hn.openbackupsaas.shared.security.PmsPrincipal;
import cn.sec.hn.openbackupsaas.shared.security.SessionTokens;
import cn.sec.hn.openbackupsaas.shared.security.TenantContext;
import cn.sec.hn.openbackupsaas.shared.security.TenantContextHolder;
import cn.sec.hn.openbackupsaas.support.IdentitySeed;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-03 认证、授权与租户隔离集成测试：**必须连真实 PostgreSQL**。
 *
 * <p>运行方式：
 * <pre>
 *   ./scripts/dev-db.sh start
 *   PMS_IT_DB=true ./mvnw -B test -Dtest=AuthIntegrationTest
 * </pre>
 * 未设置 {@code PMS_IT_DB=true} 时整类跳过（跳过而非伪造通过）。
 *
 * <p>覆盖的是 S1-03 验收项：
 * 伪造 tenant_id、跨店对象 ID、批量混入其他租户对象、撤权后旧会话失效、默认拒绝未授权写入，
 * 以及"线程复用不串租户"。**不覆盖**并发竞争（S1-13）与审计落库（S1-06）。
 */
@SpringBootTest
@ActiveProfiles("integration")
@EnabledIfEnvironmentVariable(named = "PMS_IT_DB", matches = "true")
@Sql(scripts = "/db/integration-cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AuthIntegrationTest {

    private static final String[] READ_PERMISSION = {"identity:read"};

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private IdentityMapper identityMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Authz authz;

    @Test
    void loginReturnsTokenAndScopedIdentity() {
        IdentitySeed.Seed seed = seed("a1", READ_PERMISSION, true);

        AuthenticationService.LoginResult result = authenticationService.login(
                seed.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD);

        assertNotNull(result.token());
        assertFalse(result.token().isBlank());
        assertEquals(seed.tenantId(), result.principal().tenantId(), "租户必须来自服务端会话");
        assertEquals(Set.of(seed.hotelId()), result.principal().hotelIds(), "门店范围只含已授权门店");
        assertEquals(Set.of("identity:read"), result.principal().permissions());
        assertTrue(result.expiresAt().isAfter(OffsetDateTime.now()));
    }

    @Test
    void loginFailuresShareOneMessageSoAccountEnumerationFails() {
        IdentitySeed.Seed seed = seed("a2", READ_PERMISSION, true);
        IdentitySeed.Seed other = seed("a3", new String[0], false);

        String wrongPassword = assertThrows(UnauthenticatedException.class,
                () -> authenticationService.login(seed.tenantCode(), seed.username(), "Wrong-Password-1"))
                .getMessage();
        String unknownUser = assertThrows(UnauthenticatedException.class,
                () -> authenticationService.login(seed.tenantCode(), "nobody" + seed.username(), IdentitySeed.RAW_PASSWORD))
                .getMessage();
        String wrongTenant = assertThrows(UnauthenticatedException.class,
                () -> authenticationService.login(other.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD))
                .getMessage();

        assertEquals(wrongPassword, unknownUser, "密码错与账号不存在必须同一话术");
        assertEquals(wrongPassword, wrongTenant, "租户不匹配也必须同一话术");
    }

    @Test
    void disabledUserCannotLoginAndLosesExistingSession() {
        IdentitySeed.Seed seed = seed("a4", READ_PERMISSION, true);
        String token = authenticationService.login(seed.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD)
                .token();

        jdbcTemplate.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", seed.userId());

        assertThrows(UnauthenticatedException.class, () -> authenticationService.login(
                seed.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD));
        assertTrue(authenticationService.authenticate(token).isEmpty(),
                "账号停用后，旧会话必须在下一个请求就失效");
    }

    @Test
    void suspendedTenantInvalidatesLiveSessions() {
        IdentitySeed.Seed seed = seed("a5", READ_PERMISSION, true);
        String token = authenticationService.login(seed.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD)
                .token();

        jdbcTemplate.update("UPDATE tenant SET status = 'SUSPENDED' WHERE id = ?", seed.tenantId());

        assertTrue(authenticationService.authenticate(token).isEmpty(), "租户停用后旧会话不得继续可用");
        assertThrows(UnauthenticatedException.class, () -> authenticationService.login(
                seed.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD));
    }

    @Test
    void plaintextTokenIsNeverStored() {
        IdentitySeed.Seed seed = seed("a6", READ_PERMISSION, true);
        String token = authenticationService.login(seed.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD)
                .token();

        String stored = jdbcTemplate.queryForObject("SELECT token_hash FROM user_session", String.class);
        assertEquals(SessionTokens.hash(token), stored);
        assertEquals(64, stored.length(), "库内只存 64 位十六进制 SHA-256");
        Integer plainRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_session WHERE token_hash = ?", Integer.class, token);
        assertEquals(0, plainRows, "明文令牌不得出现在任何会话列里");
    }

    @Test
    void logoutRevokesOnlyCurrentSession() {
        IdentitySeed.Seed seed = seed("a7", READ_PERMISSION, true);
        AuthenticationService.LoginResult first = authenticationService.login(
                seed.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD);
        AuthenticationService.LoginResult second = authenticationService.login(
                seed.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD);

        authenticationService.logout(first.principal());

        assertTrue(authenticationService.authenticate(first.token()).isEmpty(), "登出的会话立即失效");
        assertTrue(authenticationService.authenticate(second.token()).isPresent(), "其他设备会话不受影响");
    }

    @Test
    void changePasswordRevokesOtherSessionsButKeepsCurrent() {
        IdentitySeed.Seed seed = seed("a8", READ_PERMISSION, true);
        AuthenticationService.LoginResult current = authenticationService.login(
                seed.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD);
        AuthenticationService.LoginResult other = authenticationService.login(
                seed.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD);

        String newPassword = "Pms-Rotated-2026";
        authenticationService.changePassword(current.principal(), IdentitySeed.RAW_PASSWORD, newPassword);

        assertTrue(authenticationService.authenticate(other.token()).isEmpty(), "改密必须回收其他会话");
        assertTrue(authenticationService.authenticate(current.token()).isPresent(), "当前会话保留");
        assertTrue(authenticationService.login(seed.tenantCode(), seed.username(), newPassword).token() != null);
        assertThrows(UnauthenticatedException.class, () -> authenticationService.login(
                seed.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD));
    }

    @Test
    void expiredSessionIsRejected() {
        IdentitySeed.Seed seed = seed("a9", READ_PERMISSION, true);
        String token = authenticationService.login(seed.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD)
                .token();

        // 同时挪动 issued_at，否则会撞上 ck_user_session_expiry（expires_at > issued_at）——
        // 这条约束本身是对的：不允许出现"签发即过期"的畸形会话。
        jdbcTemplate.update("UPDATE user_session SET issued_at = now() - interval '2 hours', "
                + "expires_at = now() - interval '1 hour'");

        assertTrue(authenticationService.authenticate(token).isEmpty(), "过期会话不得通过");
    }

    @Test
    void revokedRoleLosesPermissionsImmediately() {
        IdentitySeed.Seed seed = seed("b1", READ_PERMISSION, true);
        AuthenticationService.LoginResult result = authenticationService.login(
                seed.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD);
        assertEquals(Set.of("identity:read"), result.principal().permissions());

        jdbcTemplate.update("UPDATE role SET status = 'INACTIVE' WHERE tenant_id = ?", seed.tenantId());

        Optional<PmsPrincipal> afterRevoke = authenticationService.authenticate(result.token());
        assertTrue(afterRevoke.isPresent(), "会话本身仍有效");
        assertTrue(afterRevoke.orElseThrow().permissions().isEmpty(),
                "角色停用后权限点立即消失，不需要重新登录");
    }

    @Test
    void hotelScopeDeniesForeignHotel() {
        IdentitySeed.Seed seed = seed("b2", READ_PERMISSION, true);
        TenantContext context = new TenantContext(seed.tenantId(), seed.userId(), seed.username(),
                Set.of(seed.hotelId()), Set.of("identity:read"));

        TenantContextHolder.bind(context);
        try {
            authz.requireHotel(seed.hotelId());
            assertThrows(AccessDeniedException.class, () -> authz.requireHotel(seed.secondHotelId()),
                    "同租户但未授权给该账号的门店也必须拒绝");
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void batchWithOneForeignHotelIsRejectedWholesale() {
        IdentitySeed.Seed seed = seed("b3", READ_PERMISSION, true);
        TenantContext context = new TenantContext(seed.tenantId(), seed.userId(), seed.username(),
                Set.of(seed.hotelId()), Set.of("identity:read"));

        TenantContextHolder.bind(context);
        try {
            authz.requireAllHotels(List.of(seed.hotelId()));
            assertThrows(AccessDeniedException.class,
                    () -> authz.requireAllHotels(List.of(seed.hotelId(), seed.secondHotelId())),
                    "批量里混入一个越界对象必须整体拒绝，不能部分执行");
            assertThrows(IllegalArgumentException.class, () -> authz.requireAllHotels(List.of()),
                    "空集合不等于全部门店");
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void crossTenantHotelScopeRejectedByDatabase() {
        IdentitySeed.Seed seedA = seed("b4", READ_PERMISSION, true);
        Tenant tenantB = organizationService.createTenant("t-other", "另一租户");
        Hotel hotelB = organizationService.createHotel(tenantB.id(), null, "hb", "外部店", "Asia/Shanghai");

        assertThrows(DataIntegrityViolationException.class,
                () -> identityMapper.addHotelScope(seedA.tenantId(), seedA.userId(), hotelB.id()),
                "fk_user_hotel_scope_hotel 必须在数据库层拒绝跨租户数据范围");
    }

    @Test
    void forgedTenantIdInRequestCannotWidenScope() {
        IdentitySeed.Seed seed = seed("b5", READ_PERMISSION, true);
        Tenant tenantB = organizationService.createTenant("t-forged", "伪造目标租户");

        // 攻击面：调用方在请求里塞 tenant_id。上下文只认服务端会话，因此伪造值不产生任何效果。
        TenantContext forged = new TenantContext(tenantB.id(), seed.userId(), seed.username(),
                Set.of(seed.hotelId()), Set.of("identity:read"));

        TenantContextHolder.bind(forged);
        try {
            assertEquals(tenantB.id(), authz.tenantId(), "上下文里的租户就是绑定的那个，与请求参数无关");
            assertThrows(AccessDeniedException.class, () -> authz.requireHotel(seed.secondHotelId()));
        } finally {
            TenantContextHolder.clear();
        }

        // 真实会话下，该账号只能看到自己租户内的门店范围
        AuthenticationService.LoginResult login = authenticationService.login(
                seed.tenantCode(), seed.username(), IdentitySeed.RAW_PASSWORD);
        assertEquals(Set.of(seed.hotelId()), login.principal().hotelIds());
    }

    @Test
    void threadReuseDoesNotCarryTenantContext() {
        IdentitySeed.Seed first = seed("b6", READ_PERMISSION, true);
        IdentitySeed.Seed second = seed("b7", new String[0], false);

        // 模拟同一线程先后处理两个请求：第一个请求绑定后必须清理，否则第二个请求会看到别人的租户。
        TenantContextHolder.bind(new TenantContext(first.tenantId(), first.userId(), first.username(),
                Set.of(first.hotelId()), Set.of("identity:read")));
        TenantContextHolder.clear();

        assertFalse(TenantContextHolder.isBound(), "请求结束后线程不得残留租户上下文");

        TenantContextHolder.bind(new TenantContext(second.tenantId(), second.userId(), second.username(),
                Set.of(second.hotelId()), Set.of()));
        try {
            assertEquals(second.tenantId(), authz.tenantId());
            assertTrue(authz.context().permissions().isEmpty());
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void explicitTaskContextDoesNotLeakIntoCaller() {
        IdentitySeed.Seed seed = seed("b8", READ_PERMISSION, true);
        UUID beforeTenant = seed.tenantId();
        TenantContextHolder.bind(new TenantContext(beforeTenant, seed.userId(), seed.username(),
                Set.of(seed.hotelId()), Set.of("identity:read")));
        try {
            UUID taskTenant = UUID.randomUUID();
            TenantContext taskContext = new TenantContext(taskTenant, UUID.randomUUID(), "worker",
                    Set.of(), Set.of());
            UUID seenInside = TenantContextHolder.callAs(taskContext, () -> TenantContextHolder.require().tenantId());

            assertEquals(taskTenant, seenInside, "任务线程必须看到任务自己的租户");
            assertEquals(beforeTenant, TenantContextHolder.require().tenantId(),
                    "任务结束后调用方上下文必须恢复，不能被任务覆盖");
        } finally {
            TenantContextHolder.clear();
        }
    }

    private IdentitySeed.Seed seed(String suffix, String[] permissions, boolean grantFirstHotel) {
        return IdentitySeed.seed(organizationService, identityMapper, passwordEncoder, suffix, permissions,
                grantFirstHotel);
    }
}
