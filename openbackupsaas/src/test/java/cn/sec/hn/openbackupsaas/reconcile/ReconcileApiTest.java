package cn.sec.hn.openbackupsaas.reconcile;

import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow;
import cn.sec.hn.openbackupsaas.inventory.service.InventoryService;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reconcile.service.ReconcileService;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationCommandService;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.support.IdentitySeed;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对账接口的 HTTP 验收（S2-05），**必须连真实 PostgreSQL**。
 *
 * <p>重点不是"接口能返回 200"，而是权限与语义边界：
 * <ul>
 *   <li>只读权限不能触发扫描、不能修复（那会让看板上的问题凭空消失）；</li>
 *   <li>事实类差异点"修复"必须 409——系统不知道该改哪一边，假装修好只会把矛盾藏起来；</li>
 *   <li>跨租户门店 404，不泄露对象存在性；</li>
 *   <li>忽略必须给原因。</li>
 * </ul>
 */
@AutoConfigureMockMvc
class ReconcileApiTest extends PostgresIntegrationTest {

    private static final LocalDate FROM = LocalDate.of(2026, 6, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 4);

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
    private RoomService roomService;
    @Autowired
    private ReservationCommandService reservationCommandService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private ReconcileService reconcileService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------
    // 权限分层
    // ------------------------------------------------------------------

    @Test
    void summaryRequiresReadPermission() throws Exception {
        Ctx ctx = context("rc1", new String[0]);

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reconcile/summary", ctx.hotelId)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.outcome").value("REJECTED"));
    }

    @Test
    void readPermissionCannotRunOrRepair() throws Exception {
        Ctx ctx = context("rc2", new String[]{"reconcile:read"});
        UUID findingId = derivedFinding(ctx);

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reconcile/summary", ctx.hotelId)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/hotels/{hotelId}/reconcile/run", ctx.hotelId)
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/hotels/{hotelId}/reconcile/findings/{id}/repair",
                        ctx.hotelId, findingId)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Test
    void summaryReportsCountsAndWatermark() throws Exception {
        Ctx ctx = context("rc3", new String[]{"reconcile:read", "reconcile:operate"});
        derivedFinding(ctx);

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reconcile/summary", ctx.hotelId)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openDerived").isNumber())
                .andExpect(jsonPath("$.openTotal").isNumber())
                // 水位是"最后一个已扫过的住宿日"，扫过 [FROM, TO) 即 TO 前一天
                .andExpect(jsonPath("$.scannedThrough").value(TO.minusDays(1).toString()))
                // 最近一批必须回传：界面要靠它区分"没跑过对账"与"跑过且没问题"
                .andExpect(jsonPath("$.lastRun.status").value("COMPLETED"));
    }

    @Test
    void findingsCanBeFilteredBySeverity() throws Exception {
        Ctx ctx = context("rc4", new String[]{"reconcile:read", "reconcile:operate"});
        derivedFinding(ctx);

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reconcile/findings", ctx.hotelId)
                        .param("severity", "DERIVED")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("MISSING_PROJECTION"))
                .andExpect(jsonPath("$[0].severity").value("DERIVED"));

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reconcile/findings", ctx.hotelId)
                        .param("severity", "FACT")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void runsAreListedNewestFirst() throws Exception {
        Ctx ctx = context("rc5", new String[]{"reconcile:read", "reconcile:operate"});
        derivedFinding(ctx);

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reconcile/runs", ctx.hotelId)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].triggerSource").value("MANUAL"))
                .andExpect(jsonPath("$[0].windowFrom").value(FROM.toString()));
    }

    // ------------------------------------------------------------------
    // 操作
    // ------------------------------------------------------------------

    @Test
    void manualRunRequiresOperateAndReturnsCounters() throws Exception {
        Ctx ctx = context("rc6", new String[]{"reconcile:read", "reconcile:operate"});
        UUID roomTypeId = roomType(ctx);
        book(ctx, roomTypeId);

        mockMvc.perform(post("/api/v1/hotels/{hotelId}/reconcile/run", ctx.hotelId)
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .param("autoRepair", "true")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowFrom").value(FROM.toString()))
                .andExpect(jsonPath("$.triggerSource").value("MANUAL"))
                .andExpect(jsonPath("$.openFindings").isNumber());
    }

    @Test
    void derivedFindingCanBeRepaired() throws Exception {
        Ctx ctx = context("rc7", new String[]{"reconcile:read", "reconcile:operate"});
        UUID findingId = derivedFinding(ctx);
        int openBefore = openCount(ctx, "DERIVED");

        mockMvc.perform(post("/api/v1/hotels/{hotelId}/reconcile/findings/{id}/repair",
                        ctx.hotelId, findingId)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        // 只修被点的那一条：同一次重建可能顺手让别的差异也消失，但那些工单要由下一轮扫描
        // 按"未再发现"自动退场，而不是在这次请求里一起标成已修复（没被处置的工单不该被动过）
        assertEquals("REPAIRED", findingStatus(ctx, findingId));
        assertEquals(openBefore - 1, openCount(ctx, "DERIVED"),
                "只能少掉被点的那一条；其余差异留给下一轮扫描退场");
    }

    @Test
    void factFindingRepairIsRejectedWith409() throws Exception {
        Ctx ctx = context("rc8", new String[]{"reconcile:read", "reconcile:operate"});
        UUID roomTypeId = roomType(ctx);
        book(ctx, roomTypeId);
        inventoryService.rebuild(new InventoryWindow(ctx.tenantId, ctx.hotelId, null, FROM, TO));
        // 订单被直接改成已取消，占用仍是 HELD：典型的交易事实矛盾
        jdbcTemplate.update("UPDATE reservation SET status = 'CANCELLED' WHERE tenant_id = ? AND hotel_id = ?",
                ctx.tenantId, ctx.hotelId);
        runReconcile(ctx, false);
        UUID factId = firstFindingId(ctx, "FACT");

        mockMvc.perform(post("/api/v1/hotels/{hotelId}/reconcile/findings/{id}/repair", ctx.hotelId, factId)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.outcome").value("CONFLICT"));
    }

    @Test
    void ignoreRequiresReason() throws Exception {
        Ctx ctx = context("rc9", new String[]{"reconcile:read", "reconcile:operate"});
        UUID findingId = derivedFinding(ctx);

        mockMvc.perform(post("/api/v1/hotels/{hotelId}/reconcile/findings/{id}/ignore", ctx.hotelId, findingId)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/hotels/{hotelId}/reconcile/findings/{id}/ignore", ctx.hotelId, findingId)
                        .param("reason", "投影将在今晚维护窗口重建")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reconcile/findings", ctx.hotelId)
                        .param("status", "SETTLED")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("IGNORED"))
                .andExpect(jsonPath("$[0].settleReason").value("投影将在今晚维护窗口重建"));
    }

    // ------------------------------------------------------------------
    // 越权
    // ------------------------------------------------------------------

    @Test
    void hotelOfAnotherTenantIsNotFound() throws Exception {
        Ctx ctx = context("rc10", new String[]{"reconcile:read", "reconcile:operate"});
        Ctx other = context("rc10b", new String[]{"reconcile:read", "reconcile:operate"});

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reconcile/summary", other.hotelId)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/hotels/{hotelId}/reconcile/run", other.hotelId)
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isNotFound());
    }

    @Test
    void windowLongerThanAllowedIsRejected() throws Exception {
        Ctx ctx = context("rc11", new String[]{"reconcile:read", "reconcile:operate"});

        mockMvc.perform(post("/api/v1/hotels/{hotelId}/reconcile/run", ctx.hotelId)
                        .param("from", FROM.toString())
                        .param("to", FROM.plusDays(InventoryWindow.MAX_WINDOW_DAYS + 1).toString())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isBadRequest());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 造一条派生差异（缺失投影）并跑一轮对账，返回工单 id。 */
    private UUID derivedFinding(Ctx ctx) throws Exception {
        UUID roomTypeId = roomType(ctx);
        book(ctx, roomTypeId);
        inventoryService.rebuild(new InventoryWindow(ctx.tenantId, ctx.hotelId, null, FROM, TO));
        jdbcTemplate.update("DELETE FROM room_inventory_day WHERE tenant_id = ? AND hotel_id = ? "
                + "AND stay_date = ?", ctx.tenantId, ctx.hotelId, FROM);
        // 前置条件用服务层准备，不走 HTTP：否则"只读权限"的用例连数据都造不出来，
        // 而它要测的恰恰是"只读不能触发扫描"。
        reconcileService.run(ctx.tenantId, ctx.hotelId,
                new InventoryWindow(ctx.tenantId, ctx.hotelId, null, FROM, TO), "MANUAL", null, false);
        return firstFindingId(ctx, "DERIVED");
    }

    private void runReconcile(Ctx ctx, boolean autoRepair) throws Exception {
        mockMvc.perform(post("/api/v1/hotels/{hotelId}/reconcile/run", ctx.hotelId)
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .param("autoRepair", String.valueOf(autoRepair))
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk());
    }

    private int openCount(Ctx ctx, String severity) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconcile_finding WHERE tenant_id = ? AND hotel_id = ? "
                        + "AND severity = ? AND status = 'OPEN'",
                Integer.class, ctx.tenantId, ctx.hotelId, severity);
        return count == null ? 0 : count;
    }

    private String findingStatus(Ctx ctx, UUID findingId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM reconcile_finding WHERE tenant_id = ? AND hotel_id = ? AND finding_id = ?",
                String.class, ctx.tenantId, ctx.hotelId, findingId);
    }

    private UUID firstFindingId(Ctx ctx, String severity) {
        UUID id = jdbcTemplate.queryForObject(
                "SELECT finding_id FROM reconcile_finding WHERE tenant_id = ? AND hotel_id = ? "
                        + "AND severity = ? AND status = 'OPEN' ORDER BY last_seen_at DESC LIMIT 1",
                UUID.class, ctx.tenantId, ctx.hotelId, severity);
        if (id == null) {
            throw new IllegalStateException("前置条件失败：没有 " + severity + " 类型的工单");
        }
        return id;
    }

    private UUID roomType(Ctx ctx) {
        RoomType type = roomService.createRoomType(ctx.tenantId, ctx.hotelId, "std", "标准间", 2, 0, null);
        roomService.createRoom(ctx.tenantId, ctx.hotelId, type.id(), "0101", 1, null);
        roomService.createRoom(ctx.tenantId, ctx.hotelId, type.id(), "0102", 2, null);
        return type.id();
    }

    private void book(Ctx ctx, UUID roomTypeId) {
        reservationCommandService.createReservation(ctx.tenantId,
                new ReservationCommandService.CreateCommand(ctx.hotelId, "test-client",
                        "create-" + UUID.randomUUID(), ReservationSourceType.FRONTDESK,
                        List.of(new ReservationCommandService.CreateCommand.LineCommand(
                                roomTypeId, StayRange.of(FROM, FROM.plusDays(2))))));
    }

    private Ctx context(String suffix, String[] permissions) throws Exception {
        IdentitySeed.Seed seed = IdentitySeed.seed(organizationService, identityMapper, passwordEncoder,
                suffix, permissions, true);
        return new Ctx(seed.tenantId(), seed.hotel().id(), login(seed));
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

    private record Ctx(UUID tenantId, UUID hotelId, String token) {
    }
}
