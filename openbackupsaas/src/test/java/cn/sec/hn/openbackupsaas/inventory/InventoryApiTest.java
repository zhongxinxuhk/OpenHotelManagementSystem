package cn.sec.hn.openbackupsaas.inventory;

import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
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
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S1-12 房态接口的 HTTP 验收：**必须连真实 PostgreSQL**。
 *
 * <p>验证的是权限分层与参数边界，而不是再算一遍余量（那是 {@link InventoryIntegrationTest} 的事）：
 * 只读权限不能重建投影、窗口超限直接 400、跨租户门店 404。
 */
@AutoConfigureMockMvc
class InventoryApiTest extends PostgresIntegrationTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 4);

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

    @Test
    void availabilityRequiresReadPermission() throws Exception {
        Ctx ctx = context("b1", new String[0]);

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/availability", ctx.hotelId)
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.outcome").value("REJECTED"));
    }

    @Test
    void availabilityReturnsPerNightRowsAndBookableCount() throws Exception {
        Ctx ctx = context("b2", new String[]{"inventory:read"});

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/availability", ctx.hotelId)
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                // 半开区间：10/1—10/4 是三晚
                .andExpect(jsonPath("$.nights").value(3))
                .andExpect(jsonPath("$.days.length()").value(3))
                .andExpect(jsonPath("$.days[0].totalRooms").value(2))
                .andExpect(jsonPath("$.days[0].availableRooms").value(2))
                .andExpect(jsonPath("$.bookable[0].bookableRooms").value(2));
    }

    @Test
    void windowOverLimitGets400() throws Exception {
        Ctx ctx = context("b3", new String[]{"inventory:read"});

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/availability", ctx.hotelId)
                        .param("from", FROM.toString()).param("to", FROM.plusDays(63).toString())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.outcome").value("REJECTED"));
    }

    @Test
    void rebuildRequiresWritePermission() throws Exception {
        Ctx ctx = context("b4", new String[]{"inventory:read"});

        mockMvc.perform(post("/api/v1/hotels/{hotelId}/inventory/rebuild", ctx.hotelId)
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isForbidden());
    }

    @Test
    void rebuildThenReconcileIsClean() throws Exception {
        Ctx ctx = context("b5", new String[]{"inventory:read", "inventory:write"});

        mockMvc.perform(post("/api/v1/hotels/{hotelId}/inventory/rebuild", ctx.hotelId)
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                // 2 间房 × 3 晚
                .andExpect(jsonPath("$.writtenRows").value(6))
                .andExpect(jsonPath("$.deletedRows").value(0));

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/inventory/reconcile", ctx.hotelId)
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                // JSON 里没有 clean 字段（它是计算方法，不是 record 组件），
                // 客户端按四个差异计数判断：都为 0 即洁净
                .andExpect(jsonPath("$.missingRows").value(0))
                .andExpect(jsonPath("$.staleRows").value(0))
                .andExpect(jsonPath("$.orphanRows").value(0))
                .andExpect(jsonPath("$.ledgerMismatches").value(0))
                .andExpect(jsonPath("$.expectedRows").value(6))
                .andExpect(jsonPath("$.storedRows").value(6));
    }

    @Test
    void hotelFromAnotherTenantGets404() throws Exception {
        Ctx mine = context("b6", new String[]{"inventory:read"});
        Ctx theirs = context("b7", new String[]{"inventory:read"});

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/availability", theirs.hotelId)
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .header("Authorization", "Bearer " + mine.token))
                .andExpect(status().isNotFound());
    }

    // ===================== 辅助 =====================

    private Ctx context(String suffix, String[] permissions) throws Exception {
        IdentitySeed.Seed seed = IdentitySeed.seed(organizationService, identityMapper, passwordEncoder,
                suffix, permissions, true);
        RoomType type = roomService.createRoomType(seed.tenantId(), seed.hotelId(), "std", "标准间", 2, 0, null);
        roomService.createRoom(seed.tenantId(), seed.hotelId(), type.id(), "01" + suffix, 1, null);
        roomService.createRoom(seed.tenantId(), seed.hotelId(), type.id(), "02" + suffix, 2, null);
        return new Ctx(seed.hotelId(), login(seed));
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

    private record Ctx(UUID hotelId, String token) {
    }
}
