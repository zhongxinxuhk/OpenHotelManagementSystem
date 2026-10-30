package cn.sec.hn.openbackupsaas.ops;

import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.support.IdentitySeed;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运维处置接口的 HTTP 验收（S2-04），**必须连真实 PostgreSQL**。
 *
 * <p>逐条对验收：
 * <ol>
 *   <li>无权限人员不能重放（读/操作两个权限分层，403）；</li>
 *   <li>重放保留原幂等键，多次重放不重复执行；</li>
 *   <li>每次处置都有操作者、原因与前后状态。</li>
 * </ol>
 * 另外覆盖高风险任务的双人复核与死信处置的必须给原因。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "pms.ops.high-risk-task-types=payment-capture",
        "pms.ops.replay-extra-attempts=3"
})
class OpsApiTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private IdentityMapper identityMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------
    // 权限分层
    // ------------------------------------------------------------------

    @Test
    void pendingRequiresReadPermission() throws Exception {
        Ctx ctx = context("o1", new String[0]);

        mockMvc.perform(get("/api/v1/ops/pending").header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.outcome").value("REJECTED"));
    }

    @Test
    void readPermissionCannotOperate() throws Exception {
        Ctx ctx = context("o2", new String[]{"ops:read"});
        UUID deadLetterId = insertDeadLetter(validBody());

        mockMvc.perform(get("/api/v1/ops/pending").header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deadLetters").value(1));

        mockMvc.perform(post("/api/v1/ops/dead-letters/{id}/replay", deadLetterId)
                        .param("reason", "修复后重放")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // 死信处置
    // ------------------------------------------------------------------

    @Test
    void deadLetterReplayAppliesAndLeavesFullTrail() throws Exception {
        Ctx ctx = context("o3", new String[]{"ops:read", "ops:operate"});
        UUID deadLetterId = insertDeadLetter(validBody());

        mockMvc.perform(post("/api/v1/ops/dead-letters/{id}/replay", deadLetterId)
                        .param("reason", "解析器已修复，重放确认")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        mockMvc.perform(get("/api/v1/ops/dead-letters").header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/ops/interventions").header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].targetType").value("DEAD_LETTER"))
                .andExpect(jsonPath("$[0].action").value("REPLAY"))
                .andExpect(jsonPath("$[0].operatorName").value(ctx.username))
                .andExpect(jsonPath("$[0].reason").value("解析器已修复，重放确认"))
                .andExpect(jsonPath("$[0].beforeState").value("PENDING"))
                .andExpect(jsonPath("$[0].afterState").value("REPLAYED（应用 1 个消费者）"));

        // 再重放一次：状态已不是 PENDING，必须被挡下（否则同一条报文会被处理两次）
        mockMvc.perform(post("/api/v1/ops/dead-letters/{id}/replay", deadLetterId)
                        .param("reason", "重复重放")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isConflict());
    }

    @Test
    void deadLetterReplayOfBrokenBodyStaysPending() throws Exception {
        Ctx ctx = context("o4", new String[]{"ops:read", "ops:operate"});
        UUID deadLetterId = insertDeadLetter("{不是合法 JSON");

        mockMvc.perform(post("/api/v1/ops/dead-letters/{id}/replay", deadLetterId)
                        .param("reason", "先试一次")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.applied").value(false));

        // 仍然 PENDING：以后修复消费者还能再放
        mockMvc.perform(get("/api/v1/ops/dead-letters").header("Authorization", "Bearer " + ctx.token))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void dismissRequiresReasonAndSettles() throws Exception {
        Ctx ctx = context("o5", new String[]{"ops:read", "ops:operate"});
        UUID deadLetterId = insertDeadLetter("{}");

        mockMvc.perform(post("/api/v1/ops/dead-letters/{id}/dismiss", deadLetterId)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/ops/dead-letters/{id}/dismiss", deadLetterId)
                        .param("reason", "重复投递的历史脏数据，确认无需处理")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        mockMvc.perform(get("/api/v1/ops/dead-letters").header("Authorization", "Bearer " + ctx.token))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unknownDeadLetterReturnsNotFound() throws Exception {
        Ctx ctx = context("o6", new String[]{"ops:read", "ops:operate"});

        mockMvc.perform(post("/api/v1/ops/dead-letters/{id}/replay", UUID.randomUUID())
                        .param("reason", "随便试试")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 外部任务处置：幂等键保留、次数上限延长、双人复核
    // ------------------------------------------------------------------

    @Test
    void replayKeepsIdempotencyKeyExtendsBudgetAndCannotRepeat() throws Exception {
        Ctx ctx = context("o7", new String[]{"ops:read", "ops:operate"});
        UUID taskId = insertDeadTask(ctx.tenantId, "outbound-push", "idem-" + UUID.randomUUID(), 2, 2);

        mockMvc.perform(post("/api/v1/ops/external-tasks/{id}/replay", taskId)
                        .param("reason", "渠道恢复，重放推送")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        Map<String, Object> task = jdbcTemplate.queryForMap(
                "SELECT status, attempts, max_attempts, idempotency_key FROM external_task WHERE task_id = ?",
                taskId);
        org.junit.jupiter.api.Assertions.assertEquals("PENDING", task.get("status"));
        org.junit.jupiter.api.Assertions.assertEquals(2, ((Number) task.get("attempts")).intValue(),
                "重放不重置尝试计数：否则台账的 attempt_no 会与历史行冲突");
        org.junit.jupiter.api.Assertions.assertEquals(5, ((Number) task.get("max_attempts")).intValue(),
                "重放应延长次数上限（原 2 + 3）");
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(task.get("idempotency_key")).startsWith("idem-"),
                "幂等键必须原样保留，换键等于允许对方再执行一次");

        // 第二次重放：已经回到 PENDING，不能再重放（否则同一笔会发两次）
        mockMvc.perform(post("/api/v1/ops/external-tasks/{id}/replay", taskId)
                        .param("reason", "重复重放")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isConflict());
    }

    @Test
    void highRiskReplayNeedsDistinctReviewer() throws Exception {
        Ctx ctx = context("o8", new String[]{"ops:read", "ops:operate"});
        UUID taskId = insertDeadTask(ctx.tenantId, "payment-capture", "idem-" + UUID.randomUUID(), 1, 1);

        // 高风险任务不给复核人 → 拒绝
        mockMvc.perform(post("/api/v1/ops/external-tasks/{id}/replay", taskId)
                        .param("reason", "重放扣款")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("需要复核人")));

        // 复核人 = 操作者本人 → 拒绝（单人可完成的复核不是复核）
        mockMvc.perform(post("/api/v1/ops/external-tasks/{id}/replay", taskId)
                        .param("reason", "重放扣款")
                        .param("reviewer", ctx.username)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("不能是操作者本人")));

        // 第二位操作者 → 通过，且留痕里有复核人
        mockMvc.perform(post("/api/v1/ops/external-tasks/{id}/replay", taskId)
                        .param("reason", "已与财务核对，重放扣款")
                        .param("reviewer", "reviewer-ops-2")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/ops/interventions").header("Authorization", "Bearer " + ctx.token))
                .andExpect(jsonPath("$[0].reviewerName").value("reviewer-ops-2"));
    }

    @Test
    void crossTenantTaskIsNotFound() throws Exception {
        Ctx ctx = context("o9", new String[]{"ops:read", "ops:operate"});
        UUID otherTenantTask = insertDeadTask(UUID.randomUUID(), "outbound-push",
                "idem-" + UUID.randomUUID(), 1, 1);

        mockMvc.perform(post("/api/v1/ops/external-tasks/{id}/replay", otherTenantTask)
                        .param("reason", "越权重放")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private UUID insertDeadLetter(String body) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO dead_letter (source, message_body, error) VALUES ('pms.inbox', ?, '历史缺陷') "
                        + "RETURNING dead_letter_id", UUID.class, body);
    }

    private UUID insertDeadTask(UUID tenantId, String taskType, String idempotencyKey,
                                int attempts, int maxAttempts) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO external_task (tenant_id, task_type, channel, idempotency_key, payload, "
                        + "status, attempts, max_attempts, completed_at) "
                        + "VALUES (?, ?, 'chan', ?, '{}'::jsonb, 'DEAD', ?, ?, now()) RETURNING task_id",
                UUID.class, tenantId, taskType, idempotencyKey, attempts, maxAttempts);
    }

    /** 一条能正常解析并通过消费的事件信封（用于验证"重放会真的重新走一遍"）。 */
    private String validBody() {
        return """
                {"eventId":"%s","eventType":"ReservationCreated","schemaVersion":1,
                 "occurredAt":"2026-10-30T00:00:00Z","tenantId":"%s","aggregateType":"Reservation",
                 "aggregateId":"%s","aggregateVersion":1,"eventOrdinal":1,
                 "payload":{"no":"R-1"}}""".formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private Ctx context(String suffix, String[] permissions) throws Exception {
        IdentitySeed.Seed seed = IdentitySeed.seed(organizationService, identityMapper, passwordEncoder,
                suffix, permissions, true);
        return new Ctx(seed.tenantId(), seed.username(), login(seed));
    }

    private String login(IdentitySeed.Seed seed) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "tenantCode", seed.tenantCode(),
                "username", seed.username(),
                "password", IdentitySeed.RAW_PASSWORD));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return String.valueOf(payload.get("token"));
    }

    private record Ctx(UUID tenantId, String username, String token) {
    }
}
