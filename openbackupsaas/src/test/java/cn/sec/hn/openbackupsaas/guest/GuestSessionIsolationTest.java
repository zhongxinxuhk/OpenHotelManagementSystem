package cn.sec.hn.openbackupsaas.guest;

import cn.sec.hn.openbackupsaas.guest.domain.GuestAccount;
import cn.sec.hn.openbackupsaas.guest.domain.GuestProvider;
import cn.sec.hn.openbackupsaas.guest.service.GuestAccountService;
import cn.sec.hn.openbackupsaas.guest.service.GuestAuthenticationService;
import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.platform.service.PlatformAuthenticationService;
import cn.sec.hn.openbackupsaas.shared.security.SessionTokens;
import cn.sec.hn.openbackupsaas.support.IdentitySeed;
import cn.sec.hn.openbackupsaas.support.PlatformSeed;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 三条身份平面的会话隔离验收（ADR-019）。
 *
 * <h2>这个类在防什么</h2>
 * "住客会话不复用员工会话、角色完全隔离"这句话，做成一句注释很容易，
 * 做成一堵墙很难，而失败的形态极其隐蔽：
 * <ul>
 *   <li><b>令牌跨平面通用</b>：某个过滤器"顺手兜底查了另一张表"，于是住客令牌
 *       能调商家接口。功能测试全绿，因为没人会去试这个组合。</li>
 *   <li><b>哈希不分离</b>：同一个令牌串在三张表里的哈希一模一样。此时"兜底查"
 *       不但能命中，还很难在 code review 里被看出来——那一行看起来只是在查会话。</li>
 *   <li><b>住客链绑了租户上下文</b>：住客请求带着某个 tenant 跑，任何按"当前租户"
 *       取数的代码都会把住客当成该租户的调用方。</li>
 *   <li><b>账户关闭后会话仍可用</b>：库里状态变了，但没有人撤销会话。</li>
 * </ul>
 * 这四条都只能靠"拿一个平面的令牌去打另一个平面"这类断言发现。
 */
@AutoConfigureMockMvc
class GuestSessionIsolationTest extends PostgresIntegrationTest {

    private static final String GUEST_PHONE = "+8613900139001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GuestAccountService guestService;

    @Autowired
    private GuestAuthenticationService guestAuthService;

    @Autowired
    private PlatformAuthenticationService platformAuthService;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private IdentityMapper identityMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 住客平面自身 ====================

    @Test
    void guestTokenReadsOwnProfile() throws Exception {
        GuestAccount account = guestService.register(GuestProvider.PHONE, GUEST_PHONE, "旅客甲");
        String token = guestAuthService.issueSession(account.id()).token();

        mockMvc.perform(get("/api/v1/guest/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("旅客甲"))
                .andExpect(jsonPath("$.verificationStatus").value("UNVERIFIED"))
                // 没实名就不能干别的：这个开关让客户端不用自己由 status 推导
                .andExpect(jsonPath("$.realNameRequired").value(true));
    }

    @Test
    void guestMeDoesNotLeakInternalId() throws Exception {
        // ADR-018：客户端不感知主身份 id。接口一旦返回它，客户端就会开始拿它当用户标识用，
        // 之后再想换标识就换不动了。
        GuestAccount account = guestService.register(GuestProvider.PHONE, GUEST_PHONE, "旅客甲");
        String token = guestAuthService.issueSession(account.id()).token();

        MvcResult result = mockMvc.perform(get("/api/v1/guest/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        Assertions.assertFalse(body.contains(account.id().toString()),
                "住客自助接口不应返回主身份 UUID：" + body);
    }

    @Test
    void guestRequestsWithoutTokenAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/guest/me"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== 跨平面：这才是本类的重点 ====================

    @Test
    void guestTokenIsRejectedOnMerchantSurface() throws Exception {
        // 住客令牌打商家接口必须 401。若这里通过，等于任何一个住客都能读订单与房态。
        GuestAccount account = guestService.register(GuestProvider.PHONE, GUEST_PHONE, "旅客甲");
        String guestToken = guestAuthService.issueSession(account.id()).token();

        mockMvc.perform(get("/api/v1/hotels").header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void merchantTokenIsRejectedOnGuestSurface() throws Exception {
        // 反向也要挡：员工令牌不该被住客链认出来。
        IdentitySeed.Seed seed = IdentitySeed.seed(organizationService, identityMapper,
                passwordEncoder, "gs1", new String[]{"org:read"}, true);
        String merchantToken = loginMerchant(seed);

        mockMvc.perform(get("/api/v1/guest/me").header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void platformTokenIsRejectedOnGuestAndMerchantSurfaces() throws Exception {
        String platformToken = loginPlatform("iso-admin", "guest.verification.read");

        mockMvc.perform(get("/api/v1/guest/me").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/hotels").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenHashesAreDomainSeparated() {
        // 这一条是上面三条跨平面断言的"为什么能成立"：
        // 同一个明文令牌在三个平面派生出三个不同的哈希，所以在库里互相查不到。
        // 如果哪天有人把域前缀去掉，这里会先红——而不是等到某次渗透测试才发现。
        String token = "the-same-plaintext-token-for-all-three-planes";

        String merchant = SessionTokens.hash(token);
        String guest = cn.sec.hn.openbackupsaas.guest.security.GuestSessionTokens.hash(token);
        String platform = cn.sec.hn.openbackupsaas.platform.security.PlatformSessionTokens.hash(token);

        Assertions.assertNotEquals(merchant, guest, "住客令牌哈希必须与员工的不同");
        Assertions.assertNotEquals(merchant, platform, "平台令牌哈希必须与员工的不同");
        Assertions.assertNotEquals(guest, platform, "住客与平台的令牌哈希也必须不同");
        // 三条都仍是 64 位十六进制：域分离不改变存储形态，列约束与索引照旧
        for (String hash : List.of(merchant, guest, platform)) {
            Assertions.assertEquals(64, hash.length());
        }
    }

    @Test
    void closedAccountLosesAccessImmediately() throws Exception {
        // 关账户若不撤销会话，那个人还能继续用——"关掉"就成了只挡住新登录。
        GuestAccount account = guestService.register(GuestProvider.PHONE, GUEST_PHONE, "旅客甲");
        String token = guestAuthService.issueSession(account.id()).token();

        mockMvc.perform(get("/api/v1/guest/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        jdbcTemplate.update("UPDATE guest_account SET status = 'CLOSED' WHERE id = ?", account.id());

        // 这里**不**显式撤销会话：findUsableSession 的 SQL 里带了 a.status = 'ACTIVE'，
        // 所以关账户本身就足以让会话失效。断言的是这条 SQL 条件真的在生效。
        mockMvc.perform(get("/api/v1/guest/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokeAllSessionsKicksLiveSessions() throws Exception {
        GuestAccount account = guestService.register(GuestProvider.PHONE, GUEST_PHONE, "旅客甲");
        String first = guestAuthService.issueSession(account.id()).token();
        String second = guestAuthService.issueSession(account.id()).token();

        int revoked = guestAuthService.revokeAllSessions(account.id(), "GUEST_REQUEST");
        Assertions.assertEquals(2, revoked, "应撤销该住客全部有效会话");

        for (String token : List.of(first, second)) {
            mockMvc.perform(get("/api/v1/guest/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ==================== 夹具 ====================

    private String loginMerchant(IdentitySeed.Seed seed) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "tenantCode", seed.tenant().code(),
                "username", seed.username(),
                "password", IdentitySeed.RAW_PASSWORD));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asString();
    }

    /** 造一个平台账号并登录，返回令牌（账号由 {@link PlatformSeed} 建立）。 */
    private String loginPlatform(String username, String... actions) {
        PlatformSeed.create(jdbcTemplate, passwordEncoder, username, "平台 " + username, actions);
        return platformAuthService.login(username, IdentitySeed.RAW_PASSWORD).token();
    }
}
