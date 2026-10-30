package cn.sec.hn.openbackupsaas.reservation;

import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationCommandService;
import cn.sec.hn.openbackupsaas.reservation.web.ReservationView;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.support.IdentitySeed;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S1-11 查询接口的 HTTP 验收：**必须连真实 PostgreSQL**。
 *
 * <p>与 {@link ReservationQueryIntegrationTest} 的分工：那边验证 SQL 与分页语义，
 * 这里验证"安全链与错误契约真的接上了"——401/403/404 的区分、参数错误形态、
 * 以及响应里到底有没有不该出现的字段。
 *
 * <p>脱敏不靠肉眼检查：直接对返回的 JSON 文本断言"不含 tenantId / occupancyId / operationId"，
 * 这些字段一旦被谁"顺手加进视图"，用例就会红。
 */
@AutoConfigureMockMvc
class ReservationQueryApiTest extends PostgresIntegrationTest {

    private static final LocalDate IN = LocalDate.of(2026, 7, 1);
    private static final LocalDate OUT = LocalDate.of(2026, 7, 3);

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
    private ReservationCommandService commandService;

    @Test
    void unauthenticatedListGets401() throws Exception {
        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reservations", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.outcome").value("REJECTED"));
    }

    @Test
    void missingReadPermissionGets403() throws Exception {
        Ctx ctx = context("a2", new String[0]);

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reservations", ctx.seed.hotelId())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("无访问权限"))
                .andExpect(jsonPath("$.outcome").value("REJECTED"));
    }

    @Test
    void hotelOutsideScopeGets403() throws Exception {
        Ctx ctx = context("a3", new String[]{"reservation:read"});

        // 同租户、但不在该账号的门店范围内 → 403（不是 404：门店确实存在）
        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reservations", ctx.seed.secondHotelId())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isForbidden());
    }

    @Test
    void hotelFromAnotherTenantGets404() throws Exception {
        Ctx mine = context("a4", new String[]{"reservation:read"});
        Ctx theirs = context("a5", new String[]{"reservation:read"});

        // 跨租户的门店 id：必须是 404，不能是 403 —— 403 会暴露"这家门店存在"
        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reservations", theirs.seed.hotelId())
                        .header("Authorization", "Bearer " + mine.token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.outcome").value("REJECTED"));
    }

    @Test
    void malformedQueryParameterGets400WithFieldError() throws Exception {
        Ctx ctx = context("a6", new String[]{"reservation:read"});

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reservations", ctx.seed.hotelId())
                        .param("status", "NOT_A_STATUS")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    @Test
    void oversizedPageGets400() throws Exception {
        Ctx ctx = context("a7", new String[]{"reservation:read"});

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reservations", ctx.seed.hotelId())
                        .param("size", "5000")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.outcome").value("REJECTED"));
    }

    @Test
    void listReturnsPagingEnvelope() throws Exception {
        Ctx ctx = context("a8", new String[]{"reservation:read"});
        createReservation(ctx);

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reservations", ctx.seed.hotelId())
                        .param("page", "1").param("size", "10")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    /**
     * 详情脱敏：不该出现的字段一个都不能有。
     *
     * <p>断言的是<b>JSON 文本</b>而不是某个 jsonPath 不存在——后者只能证明"这个路径没有值"，
     * 证明不了"没被塞到别处"。
     */
    @Test
    void detailDoesNotLeakInternalIdentifiers() throws Exception {
        Ctx ctx = context("a9", new String[]{"reservation:read"});
        ReservationView created = createReservation(ctx);

        MvcResult result = mockMvc.perform(
                        get("/api/v1/hotels/{hotelId}/reservations/{id}", ctx.seed.hotelId(), created.id())
                                .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationNo").value(created.reservationNo()))
                .andExpect(jsonPath("$.allowedActions[0]").value("CONFIRM"))
                .andExpect(jsonPath("$.lines[0].assignedRoomCode").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("tenantId"), "响应不应包含 tenantId");
        assertFalse(body.contains("occupancyId"), "响应不应包含占用 id");
        assertFalse(body.contains("operationId"), "响应不应包含操作 id");
    }

    @Test
    void detailOfUnknownReservationGets404() throws Exception {
        Ctx ctx = context("a10", new String[]{"reservation:read"});

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reservations/{id}",
                        ctx.seed.hotelId(), UUID.randomUUID())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("对象不存在"));
    }

    // ===================== 辅助 =====================

    private ReservationView createReservation(Ctx ctx) {
        return commandService.createReservation(ctx.seed.tenantId(),
                new ReservationCommandService.CreateCommand(ctx.seed.hotelId(), "test-client",
                        "create-" + ctx.seed.username(), ReservationSourceType.FRONTDESK,
                        List.of(new ReservationCommandService.CreateCommand.LineCommand(
                                ctx.roomTypeId, StayRange.of(IN, OUT)))));
    }

    private Ctx context(String suffix, String[] permissions) throws Exception {
        IdentitySeed.Seed seed = IdentitySeed.seed(organizationService, identityMapper, passwordEncoder,
                suffix, permissions, true);
        RoomType type = roomService.createRoomType(seed.tenantId(), seed.hotelId(), "std", "标准间", 2, 0, null);
        roomService.createRoom(seed.tenantId(), seed.hotelId(), type.id(), "01" + suffix, 1, null);
        return new Ctx(seed, type.id(), login(seed));
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

    private record Ctx(IdentitySeed.Seed seed, UUID roomTypeId, String token) {
    }
}
