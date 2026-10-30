package cn.sec.hn.openbackupsaas.room;

import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.support.IdentitySeed;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S1-04 接口层验收：权限点、门店归属与门店范围三段顺序，以及房型/房间的正常路径。
 *
 * <p>为什么必须走 MockMvc 而不是直接调 service：本轮是**第一次**有带数据范围校验的业务接口，
 * 三段顺序错了照样能返回 200（只是返回了别人的数据），只有 HTTP 状态码能暴露。
 */
@AutoConfigureMockMvc
class RoomApiTest extends PostgresIntegrationTest {

    private static final String[] ROOM_PERMISSIONS = {"room:read", "room:write"};

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

    @Test
    void unauthenticatedRequestGets401() throws Exception {
        IdentitySeed.Seed seed = seed("r1", ROOM_PERMISSIONS);

        mockMvc.perform(get("/api/v1/hotels/" + seed.hotelId() + "/rooms"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingRoomPermissionGets403() throws Exception {
        IdentitySeed.Seed seed = seed("r2", new String[]{"identity:read"});
        String token = login(seed);

        mockMvc.perform(get("/api/v1/hotels/" + seed.hotelId() + "/rooms")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("无访问权限"));
    }

    @Test
    void hotelOutsideDataScopeGets403() throws Exception {
        IdentitySeed.Seed seed = seed("r3", ROOM_PERMISSIONS);
        String token = login(seed);

        // 有 room 权限，但第二家门店不在该账号的数据范围内
        mockMvc.perform(get("/api/v1/hotels/" + seed.secondHotelId() + "/rooms")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void hotelOfAnotherTenantGets404NotForbidden() throws Exception {
        IdentitySeed.Seed mine = seed("r4", ROOM_PERMISSIONS);
        IdentitySeed.Seed other = seed("r5", ROOM_PERMISSIONS);
        String token = login(mine);

        mockMvc.perform(get("/api/v1/hotels/" + other.hotelId() + "/rooms")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("对象不存在"));
    }

    @Test
    void createThenListRoomTypes() throws Exception {
        IdentitySeed.Seed seed = seed("r6", ROOM_PERMISSIONS);
        String token = login(seed);

        mockMvc.perform(post("/api/v1/hotels/" + seed.hotelId() + "/room-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "DLX-King",
                                "name", "豪华大床房",
                                "capacityAdults", 3,
                                "bedType", "KING"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("dlx-king"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/hotels/" + seed.hotelId() + "/room-types")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void duplicateRoomTypeCodeGets409() throws Exception {
        IdentitySeed.Seed seed = seed("r7", ROOM_PERMISSIONS);
        String token = login(seed);
        createRoomType(token, seed.hotelId(), "std", "标准间");

        mockMvc.perform(post("/api/v1/hotels/" + seed.hotelId() + "/room-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "std", "name", "又一个标准间"))))
                .andExpect(status().isConflict());
    }

    @Test
    void createRoomThenPatchAttributes() throws Exception {
        IdentitySeed.Seed seed = seed("r8", ROOM_PERMISSIONS);
        String token = login(seed);
        String roomTypeId = createRoomType(token, seed.hotelId(), "std", "标准间");

        MvcResult created = mockMvc.perform(post("/api/v1/hotels/" + seed.hotelId() + "/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roomTypeId", roomTypeId,
                                "code", "0801",
                                "floor", 8))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("0801"))
                .andExpect(jsonPath("$.housekeepingStatus").value("DIRTY"))
                .andReturn();

        String roomId = readField(created, "id");

        mockMvc.perform(patch("/api/v1/hotels/" + seed.hotelId() + "/rooms/" + roomId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("floor", 9, "notes", "临街"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.floor").value(9))
                .andExpect(jsonPath("$.notes").value("临街"))
                .andExpect(jsonPath("$.housekeepingStatus").value("DIRTY"));
    }

    @Test
    void deactivatingRoomTypeWithActiveRoomsGets409() throws Exception {
        IdentitySeed.Seed seed = seed("r9", ROOM_PERMISSIONS);
        String token = login(seed);
        String roomTypeId = createRoomType(token, seed.hotelId(), "std", "标准间");

        mockMvc.perform(post("/api/v1/hotels/" + seed.hotelId() + "/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("roomTypeId", roomTypeId, "code", "0801"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/hotels/" + seed.hotelId() + "/room-types/" + roomTypeId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "INACTIVE"))))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidStatusValueGets400() throws Exception {
        IdentitySeed.Seed seed = seed("s1", ROOM_PERMISSIONS);
        String token = login(seed);
        String roomTypeId = createRoomType(token, seed.hotelId(), "std", "标准间");

        mockMvc.perform(post("/api/v1/hotels/" + seed.hotelId() + "/room-types/" + roomTypeId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELETED\"}"))
                .andExpect(status().isBadRequest());
    }

    private IdentitySeed.Seed seed(String suffix, String[] permissions) {
        return IdentitySeed.seed(organizationService, identityMapper, passwordEncoder, suffix, permissions, true);
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

        return readField(result, "token");
    }

    private String createRoomType(String token, java.util.UUID hotelId, String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/hotels/" + hotelId + "/room-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", code, "name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return readField(result, "id");
    }

    @SuppressWarnings("unchecked")
    private String readField(MvcResult result, String field) throws Exception {
        Map<String, Object> payload =
                objectMapper.readValue(result.getResponse().getContentAsString(), HashMap.class);
        Object value = payload.get(field);
        Assertions.assertNotNull(value, "响应缺少字段：" + field);
        return String.valueOf(value);
    }
}
