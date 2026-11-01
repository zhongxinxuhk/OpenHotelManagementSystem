package cn.sec.hn.openbackupsaas.guest;

import cn.sec.hn.openbackupsaas.guest.domain.GuestAccount;
import cn.sec.hn.openbackupsaas.guest.domain.GuestProvider;
import cn.sec.hn.openbackupsaas.guest.ownership.DevChallengeMailbox;
import cn.sec.hn.openbackupsaas.guest.ownership.OwnershipChallengeSender;
import cn.sec.hn.openbackupsaas.guest.service.GuestAccountService;
import cn.sec.hn.openbackupsaas.guest.service.GuestAuthenticationService;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 住客凭证绑定流程验收（ADR-019 §2.10，R-20261101-33）。
 *
 * <h2>这个类在防什么</h2>
 * 绑定流程的错误不是"绑不上"，而是**绑到了不该绑的地方**：
 * <ul>
 *   <li><b>挑战归属缺失</b>：拿到别人的 challengeId 就能把自己手机号绑到别人账户
 *       ——账户接管。confirm 必须校验挑战的 account_id 是当前会话的人；</li>
 *   <li><b>验证码爆破</b>：6 位数字熵只有 20 bit，没有尝试上限与短过期，
 *       在线爆破 10^6 次就能绑任意手机号；</li>
 *   <li><b>验证码重放</b>：同一个码绑两次、或绑完再 confirm，必须被一次性消费挡住；</li>
 *   <li><b>解绑到无法登录</b>：解绑最后一个凭证等于把自己锁在门外，
 *       而住客没有"平台帮恢复"的路。</li>
 * </ul>
 */
@AutoConfigureMockMvc
class GuestBindingFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "Guest-Password-2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GuestAccountService guestService;

    @Autowired
    private GuestAuthenticationService guestAuthService;

    @Autowired
    private DevChallengeMailbox devMailbox;

    // ==================== 全链路 ====================

    @Test
    void challengeThenConfirmBindsCredential() throws Exception {
        String token = registerAndLogin("binder.one");
        String phone = "+8613600136001";

        MvcResult challenge = mockMvc.perform(post("/api/v1/guest/auth/bindings/challenge")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", "PHONE", "subject", phone))))
                .andExpect(status().isOk())
                // 响应不含验证码，也只给掩码
                .andExpect(jsonPath("$.subjectMasked").value("+86****6001"))
                .andReturn();
        String challengeId = objectMapper.readTree(
                challenge.getResponse().getContentAsString()).get("challengeId").asString();

        String code = devMailbox.get("PHONE", phone);
        Assertions.assertNotNull(code, "开发信箱应已收到验证码");

        mockMvc.perform(post("/api/v1/guest/auth/bindings/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId, "code", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.provider == 'PHONE')].subject")
                        .value(org.hamcrest.Matchers.contains(phone)));
    }

    @Test
    void challengeBelongsToItsInitiatorOnly() throws Exception {
        // 账户 A 发起挑战，账户 B 拿这个 challengeId 去 confirm → 404。
        // 这一条防的是账户接管：没有它，任何登录用户都能把自己的手机号绑到别人账户。
        String tokenA = registerAndLogin("owner.a");
        String tokenB = registerAndLogin("attacker.b");

        MvcResult challenge = mockMvc.perform(post("/api/v1/guest/auth/bindings/challenge")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", "EMAIL", "subject", "owner-a@example.com"))))
                .andExpect(status().isOk())
                .andReturn();
        String challengeId = objectMapper.readTree(
                challenge.getResponse().getContentAsString()).get("challengeId").asString();

        mockMvc.perform(post("/api/v1/guest/auth/bindings/confirm")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId, "code", "000000"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void codeIsConsumedExactlyOnce() throws Exception {
        String token = registerAndLogin("replay.one");
        String phone = "+8613600136002";

        String challengeId = challenge(token, phone);
        String code = devMailbox.get("PHONE", phone);

        mockMvc.perform(post("/api/v1/guest/auth/bindings/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId, "code", code))))
                .andExpect(status().isOk());

        // 重放：同一个码再 confirm → 409（一次性消费）
        mockMvc.perform(post("/api/v1/guest/auth/bindings/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId, "code", code))))
                .andExpect(status().isConflict());
    }

    @Test
    void bruteForceIsCappedAtFiveAttempts() throws Exception {
        String token = registerAndLogin("brute.one");
        String phone = "+8613600136003";

        String challengeId = challenge(token, phone);

        // 前 5 次错码：400（验证码不正确）
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/guest/auth/bindings/confirm")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "challengeId", challengeId, "code", "00000" + i % 10))))
                    .andExpect(status().isBadRequest());
        }

        // 第 6 次即使码是对的也必须失败：挑战已作废
        String realCode = devMailbox.get("PHONE", phone);
        mockMvc.perform(post("/api/v1/guest/auth/bindings/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId, "code", realCode))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("错误次数已达上限，请重新发起归属验证"));

        // 目标手机号没有被绑上
        long bound = countCredentials(accountIdOf("brute.one"));
        Assertions.assertEquals(1, bound, "爆破被拦后不应有新凭证");
    }

    @Test
    void subjectOccupiedByAnotherAccountIsRejected() throws Exception {
        // 手机号 X 已绑在账户 A；账户 B 对 X 发起挑战 → 创建时就 409
        // （提前失败，不浪费一次发送；竞态兜底由唯一索引承担）
        String tokenA = registerAndLogin("holder.a");
        String phone = "+8613600136004";
        bindPhone(tokenA, phone);

        String tokenB = registerAndLogin("holder.b");
        mockMvc.perform(post("/api/v1/guest/auth/bindings/challenge")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", "PHONE", "subject", phone))))
                .andExpect(status().isConflict());
    }

    @Test
    void cannotUnbindTheLastCredential() throws Exception {
        // 只有一个凭证（注册时的用户名）时解绑它 → 409。
        // 解绑完没有任何登录方式 = 账户永远进不来，且住客没有平台恢复通道。
        String token = registerAndLogin("last.one");

        mockMvc.perform(delete("/api/v1/guest/auth/bindings")
                        .header("Authorization", "Bearer " + token)
                        .param("provider", "USERNAME")
                        .param("subject", "last.one"))
                .andExpect(status().isConflict());

        // 绑一个手机号之后再解绑用户名：可以（还剩手机号可登录）
        bindPhone(token, "+8613600136005");
        mockMvc.perform(delete("/api/v1/guest/auth/bindings")
                        .header("Authorization", "Bearer " + token)
                        .param("provider", "USERNAME")
                        .param("subject", "last.one"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void usernameBindingNeedsNoChallenge() throws Exception {
        // USERNAME 的归属证明是"知道密码"（ADR-019 §2.9），
        // 已认证状态下绑定新用户名等价于加一个自持凭证
        String token = registerAndLogin("userbind.one");

        mockMvc.perform(post("/api/v1/guest/auth/bindings/username")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "userbind.two",
                                "password", "Another-Password-2026"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 新用户名可以登录
        mockMvc.perform(post("/api/v1/guest/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "userbind.two",
                                "password", "Another-Password-2026"))))
                .andExpect(status().isOk());
    }

    @Test
    void noopSenderNeverPretendsToSend() {
        // 生产默认的发送器：显式抛错，绝不静默"发送成功"。
        // 静默成功比失败糟得多——用户收不到码还会一直重试。
        OwnershipChallengeSender noop = new cn.sec.hn.openbackupsaas.guest.ownership.DevChallengeMailbox.NoopOwnershipChallengeSender();
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> noop.send(new OwnershipChallengeSender.ChallengeDelivery(
                        "PHONE", "+8613800138000", "123456",
                        java.time.OffsetDateTime.now().plusMinutes(10))));
    }

    // ==================== 夹具 ====================

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/v1/guest/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", PASSWORD))))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/v1/guest/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString())
                .get("token").asString();
    }

    private String challenge(String token, String subject) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/guest/auth/bindings/challenge")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", "PHONE", "subject", subject))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("challengeId").asString();
    }

    private void bindPhone(String token, String phone) throws Exception {
        String challengeId = challenge(token, phone);
        String code = devMailbox.get("PHONE", phone);
        mockMvc.perform(post("/api/v1/guest/auth/bindings/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId, "code", code))))
                .andExpect(status().isOk());
    }

    private UUID accountIdOf(String username) {
        return guestService.resolveAccountId(GuestProvider.USERNAME, username).orElseThrow();
    }

    private long countCredentials(UUID accountId) {
        return jdbcTemplateCount(accountId);
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private long jdbcTemplateCount(UUID accountId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM guest_credential WHERE account_id = ?",
                Long.class, accountId);
        return count == null ? 0 : count;
    }
}
