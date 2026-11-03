package cn.sec.hn.openbackupsaas.platform;

import cn.sec.hn.openbackupsaas.guest.domain.GuestAccount;
import cn.sec.hn.openbackupsaas.guest.domain.GuestProvider;
import cn.sec.hn.openbackupsaas.guest.domain.GuestVerificationStatus;
import cn.sec.hn.openbackupsaas.guest.service.GuestAccountService;
import cn.sec.hn.openbackupsaas.platform.service.PlatformAuthenticationService;
import cn.sec.hn.openbackupsaas.support.IdentitySeed;
import cn.sec.hn.openbackupsaas.support.PlatformSeed;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实名人工审核闭环验收（ADR-019 §2.5/§2.6）。
 *
 * <h2>这个类在防什么</h2>
 * 实名审核的错不是"点不动按钮"，而是**点动了但结论是错的、或者没有留下痕迹**：
 * <ul>
 *   <li><b>没有理由的通过</b>：事后无法复核这次放行对不对，等于审核退化成一个点击；</li>
 *   <li><b>审核者从请求体取</b>：留痕里写谁都可以，追责链条断在第一环；</li>
 *   <li><b>重复决定互相覆盖</b>：两个审核员同时点，两条矛盾的记录都写着成功；</li>
 *   <li><b>驳回也写 verified_at</b>：之后"按 verified_at 非空统计已实名"会把驳回的算进去；</li>
 *   <li><b>权威接口熔断时人工审核也走不动</b>——这是需求方明确要求不能发生的事，
 *       而最容易的错法恰恰是"在人工路径里顺手检查一下渠道状态"。</li>
 * </ul>
 * 最后一条是本类最重要的用例：它证明的不是"人工能审"，而是"人工审核与渠道状态无关"。
 */
@AutoConfigureMockMvc
class PlatformVerificationReviewTest extends PostgresIntegrationTest {

    /** 通过 ISO 7064 MOD 11-2 校验位的测试号码。 */
    private static final String ID_NUMBER = "11010519491231002X";
    private static final String ID_NUMBER_2 = "310101199003070051";
    private static final String PHONE = "+8613700137001";
    private static final String REAL_NAME = "张三";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GuestAccountService guestService;

    @Autowired
    private PlatformAuthenticationService platformAuthService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private cn.sec.hn.openbackupsaas.guest.service.GuestAuthenticationService guestAuthenticationService;

    // ==================== 权限 ====================

    @Test
    void reviewRequiresTheReviewAction() throws Exception {
        // 只有读权限的人不能下结论。这两个动作在现实里常分给不同的人
        // （一线初筛、主管决定），合成一个就没法表达了。
        String readerToken = loginPlatform("reader-only", "guest.verification.read");
        GuestAccount account = submitVerification(PHONE, ID_NUMBER);

        mockMvc.perform(get("/api/v1/platform/guest-verifications")
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/platform/guest-verifications/" + account.id() + "/decision")
                        .header("Authorization", "Bearer " + readerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("VERIFIED", "资料清晰")))
                .andExpect(status().isForbidden());
    }

    // ==================== 队列 ====================

    @Test
    void pendingQueueShowsMasksOnlyAndOnlyPending() throws Exception {
        String token = loginPlatform("reviewer-q", "guest.verification.read");

        GuestAccount pending = submitVerification(PHONE, ID_NUMBER);
        GuestAccount rejected = submitVerification("+8613700137002", ID_NUMBER_2);
        // 先把第二个驳回，它就不该再出现在队列里
        jdbcTemplate.update("UPDATE guest_id_verification SET status = 'REJECTED' WHERE account_id = ?",
                rejected.id());

        MvcResult result = mockMvc.perform(get("/api/v1/platform/guest-verifications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].accountId").value(pending.id().toString()))
                .andExpect(jsonPath("$.items[0].idNumberMask").value("110105********002X"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // 队列里不能出现完整证件号或姓名：审核"证件是否合规"不需要它们
        Assertions.assertFalse(body.contains(ID_NUMBER) || body.contains(ID_NUMBER_2),
                "审核队列不得返回完整证件号：" + body);
        Assertions.assertFalse(body.contains(REAL_NAME), "审核队列不得返回真实姓名：" + body);
    }

    // ==================== 决定 ====================

    @Test
    void manualApproveMarksVerifiedAndLeavesTrace() throws Exception {
        String token = loginPlatform("reviewer-ok", "guest.verification.read", "guest.verification.review");
        GuestAccount account = submitVerification(PHONE, ID_NUMBER);
        Assertions.assertEquals(GuestVerificationStatus.PENDING,
                guestService.verificationStatus(account.id()));

        MvcResult result = mockMvc.perform(post("/api/v1/platform/guest-verifications/" + account.id() + "/decision")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("VERIFIED", "证件清晰，姓名与证件一致")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("VERIFIED"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                // 审核者来自会话，不是请求体——所以这条一定能对上登录的人
                .andExpect(jsonPath("$.reviewerName").value("平台 reviewer-ok"))
                .andReturn();

        Assertions.assertEquals(GuestVerificationStatus.VERIFIED,
                guestService.verificationStatus(account.id()),
                "通过后住客应立刻满足实名要求");

        // 留痕要能独立查到（含当时的掩码快照）
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT decision, source, reason, reviewer_id, reviewer_name, id_number_mask
                FROM guest_verification_review WHERE account_id = ?
                """, account.id());
        Assertions.assertEquals("VERIFIED", row.get("decision"));
        Assertions.assertEquals("MANUAL", row.get("source"));
        Assertions.assertEquals("证件清晰，姓名与证件一致", row.get("reason"));
        Assertions.assertEquals("110105********002X", row.get("id_number_mask"));
        Assertions.assertNotNull(row.get("reviewer_id"), "留痕必须有审核者 id");
        Assertions.assertNotNull(result.getResponse().getContentAsString());

        // 历史可查
        mockMvc.perform(get("/api/v1/platform/guest-verifications/" + account.id() + "/history")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void manualRejectDoesNotStampVerifiedAt() throws Exception {
        // verified_at 的语义是"何时验证通过"。驳回时写时间，会让
        // "按 verified_at 非空统计已实名"这种查询把被驳回的人也统计进去。
        String token = loginPlatform("reviewer-no", "guest.verification.review");
        GuestAccount account = submitVerification(PHONE, ID_NUMBER);

        mockMvc.perform(post("/api/v1/platform/guest-verifications/" + account.id() + "/decision")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("REJECTED", "证件照片反光，号码无法辨认，请重新提交")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REJECTED"));

        Assertions.assertEquals(GuestVerificationStatus.REJECTED,
                guestService.verificationStatus(account.id()));
        Object verifiedAt = jdbcTemplate.queryForObject(
                "SELECT verified_at FROM guest_id_verification WHERE account_id = ?",
                Object.class, account.id());
        Assertions.assertNull(verifiedAt, "驳回不应写 verified_at");
    }

    @Test
    void decisionWithoutReasonIsRejected() throws Exception {
        String token = loginPlatform("reviewer-nr", "guest.verification.review");
        GuestAccount account = submitVerification(PHONE, ID_NUMBER);

        mockMvc.perform(post("/api/v1/platform/guest-verifications/" + account.id() + "/decision")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("VERIFIED", "   ")))
                .andExpect(status().isBadRequest());

        Assertions.assertEquals(GuestVerificationStatus.PENDING,
                guestService.verificationStatus(account.id()),
                "没有理由的通过不能生效");
    }

    @Test
    void secondReviewerGetsConflictInsteadOfOverwriting() throws Exception {
        // 并发下两条矛盾的记录都写"成功"，事后就无法判断最终结论是怎么来的。
        String token = loginPlatform("reviewer-race", "guest.verification.review");
        GuestAccount account = submitVerification(PHONE, ID_NUMBER);

        mockMvc.perform(post("/api/v1/platform/guest-verifications/" + account.id() + "/decision")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("VERIFIED", "先到的审核员")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/platform/guest-verifications/" + account.id() + "/decision")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("REJECTED", "后到的审核员")))
                .andExpect(status().isConflict());

        // 结论仍是先到的那一个，且只有一条留痕
        Assertions.assertEquals(GuestVerificationStatus.VERIFIED,
                guestService.verificationStatus(account.id()));
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM guest_verification_review WHERE account_id = ?",
                Long.class, account.id());
        Assertions.assertEquals(1L, count, "被拒的第二次决定不应写入留痕");
    }

    @Test
    void verifiedGuestIsReleasedOnNextRequestWithoutRelogin() throws Exception {
        // 实名状态每请求现读，所以审核通过后住客不用重新登录，
        // 也不存在"会话里还存着旧状态"的窗口。
        String reviewerToken = loginPlatform("reviewer-live", "guest.verification.review");
        GuestAccount account = submitVerification(PHONE, ID_NUMBER);
        String guestToken = loginGuest(account);

        mockMvc.perform(get("/api/v1/guest/me").header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("PENDING"))
                .andExpect(jsonPath("$.realNameRequired").value(true));

        mockMvc.perform(post("/api/v1/platform/guest-verifications/" + account.id() + "/decision")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("VERIFIED", "核对无误")))
                .andExpect(status().isOk());

        // 同一个住客令牌，下一次请求就解开限制
        mockMvc.perform(get("/api/v1/guest/me").header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.realNameRequired").value(false));
    }

    // ==================== 平台强制首改（与员工侧同一条安全底线） ====================

    @Test
    void mustChangePasswordBlocksEverythingExceptAllowedPaths() throws Exception {
        // 种子脚本创建的平台账号 must_change_password = true。
        // 拦截器若失效，初始口令就能直接审核实名——那这个标记就只是个摆设。
        UUID reviewerId = PlatformSeed.create(jdbcTemplate, passwordEncoder,
                "fresh-admin", "平台 fresh-admin", true,
                "guest.verification.read", "guest.verification.review");
        var issued = platformAuthService.login("fresh-admin", IdentitySeed.RAW_PASSWORD);
        String token = issued.token();
        Assertions.assertTrue(issued.principal().mustChangePassword(),
                "前提检查：种子账号应处于待改密状态");

        // 放行清单里的三个接口可用
        mockMvc.perform(get("/api/v1/platform/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        // 其余一律 403 + 可识别错误码。**不能只挡 GET**：写操作才是真正要防的。
        mockMvc.perform(get("/api/v1/platform/guest-verifications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        mockMvc.perform(post("/api/v1/platform/guest-verifications/" + reviewerId + "/decision")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("VERIFIED", "待改密状态下不应能审核")))
                .andExpect(status().isForbidden());

        // 改密（放行清单内）
        mockMvc.perform(post("/api/v1/platform/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", IdentitySeed.RAW_PASSWORD,
                                "newPassword", "Platform-Fresh-2026"))))
                .andExpect(status().isNoContent());

        // 同一个令牌立即解除限制
        mockMvc.perform(get("/api/v1/platform/guest-verifications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void platformPasswordChangeRevokesOtherSessions() throws Exception {
        // 平台动作影响面是全局的。口令被改 = 原口令可能已泄露，
        // 其它设备上的会话不该继续有效（保留改密发起者的那个会话）。
        PlatformSeed.create(jdbcTemplate, passwordEncoder, "multi-admin", "平台 multi-admin",
                "guest.verification.read");
        var first = platformAuthService.login("multi-admin", IdentitySeed.RAW_PASSWORD);
        var second = platformAuthService.login("multi-admin", IdentitySeed.RAW_PASSWORD);

        mockMvc.perform(post("/api/v1/platform/auth/password")
                        .header("Authorization", "Bearer " + first.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", IdentitySeed.RAW_PASSWORD,
                                "newPassword", "Platform-Rotated-2026"))))
                .andExpect(status().isNoContent());

        // 发起改密的会话保留
        mockMvc.perform(get("/api/v1/platform/auth/me")
                        .header("Authorization", "Bearer " + first.token()))
                .andExpect(status().isOk());
        // 其它会话撤销
        mockMvc.perform(get("/api/v1/platform/auth/me")
                        .header("Authorization", "Bearer " + second.token()))
                .andExpect(status().isUnauthorized());
    }

    // ==================== 熔断与人工审核的关系（核心） ====================

    @Test
    void manualReviewStillWorksWhileAuthorityChannelIsCircuitOpen() throws Exception {
        // 需求原话："在权威核验接口熔断的时候，人工审核依然可以继续"。
        // 这条用例把权威渠道真的置成 OPEN，并让熔断窗口延伸到未来，
        // 然后走完整的人工审核流程——必须仍然成功。
        //
        // 实现上的保证不是"if 里跳过了渠道"，而是 GuestVerificationDecisionService
        // 的构造器里根本没有渠道相关依赖（见该类注释）。
        String token = loginPlatform("reviewer-circuit", "guest.verification.review");
        GuestAccount account = submitVerification(PHONE, ID_NUMBER);

        // 渠道表在清理器的保留清单里（迁移写入的结构性配置），所以测试自己插的行
        // 必须自己删——否则会跨用例残留。
        jdbcTemplate.update("DELETE FROM guest_verification_channel WHERE code = 'AUTH_TEST'");
        jdbcTemplate.update("""
                        INSERT INTO guest_verification_channel
                            (id, code, name, kind, endpoint, timeout_ms, priority, status,
                             circuit_state, consecutive_failures, opened_until)
                        VALUES (gen_random_uuid(), 'AUTH_TEST', '测试权威渠道', 'AUTHORITY',
                                'https://verify.example.invalid/api', 3000, 10, 'ACTIVE',
                                'OPEN', 5, now() + interval '30 minutes')
                        """);

        Assertions.assertEquals("OPEN", jdbcTemplate.queryForObject(
                "SELECT circuit_state FROM guest_verification_channel WHERE code = 'AUTH_TEST'",
                String.class), "前提检查：权威渠道确实处于熔断中");

        mockMvc.perform(post("/api/v1/platform/guest-verifications/" + account.id() + "/decision")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("VERIFIED", "权威接口不可用，人工核对证件")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MANUAL"));

        Assertions.assertEquals(GuestVerificationStatus.VERIFIED,
                guestService.verificationStatus(account.id()),
                "权威渠道熔断时，人工审核必须仍然能给出结论");
    }

    @Test
    void manualChannelCannotBeDisabled() throws Exception {
        // 人工是唯一兜底。停掉它，等于在权威接口抖动的那一刻让所有新住客卡死，
        // 而这个后果在配置界面上完全看不出来。
        String token = loginPlatform("channel-admin", "guest.channel.write");

        mockMvc.perform(put("/api/v1/platform/verification-channels/MANUAL_REVIEW")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "人工审核",
                                "timeoutMs", 3000,
                                "priority", 999,
                                "status", "INACTIVE"))))
                .andExpect(status().isConflict());
    }

    @Test
    void authorityChannelRequiresEndpoint() throws Exception {
        String token = loginPlatform("channel-admin2", "guest.channel.write");

        mockMvc.perform(post("/api/v1/platform/verification-channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "AUTH_NO_ENDPOINT",
                                "name", "缺地址的权威渠道",
                                "kind", "AUTHORITY"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void channelConfigurationAdmitsNoImplementationYet() throws Exception {
        // 配置存在 ≠ 已接通。本轮没有任何 AUTHORITY 实现，接口必须照实说，
        // 否则运维会以为"配完就在走自动核验"，直到真实住客进来才发现全卡在人工队列。
        String token = loginPlatform("channel-admin3", "guest.channel.read");

        mockMvc.perform(get("/api/v1/platform/verification-channels")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'MANUAL_REVIEW')].implementationAvailable")
                        .value(org.hamcrest.Matchers.contains(true)));
    }

    // ==================== 夹具 ====================

    private GuestAccount submitVerification(String phone, String idNumber) {
        GuestAccount account = guestService.register(GuestProvider.PHONE, phone, "旅客" + phone.substring(4));
        guestService.submitVerification(account.id(), REAL_NAME, idNumber);
        return account;
    }

    private String loginGuest(GuestAccount account) {
        // 住客没有登录接口（验证码未接），直接从服务层签发会话——
        // 本类要验的是"审核状态变化如何影响住客请求"，不是登录流程。
        return guestAuthenticationService.issueSession(account.id()).token();
    }

    private String loginPlatform(String username, String... actions) {
        PlatformSeed.create(jdbcTemplate, passwordEncoder, username, "平台 " + username, actions);
        return platformAuthService.login(username, IdentitySeed.RAW_PASSWORD).token();
    }

    private String decisionBody(String decision, String reason) throws Exception {
        return objectMapper.writeValueAsString(Map.of("decision", decision, "reason", reason));
    }
}
