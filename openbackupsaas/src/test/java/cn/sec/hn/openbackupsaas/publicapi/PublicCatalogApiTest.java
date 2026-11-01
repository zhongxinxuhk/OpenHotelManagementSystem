package cn.sec.hn.openbackupsaas.publicapi;

import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.room.domain.PhysicalRoom;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.support.IdentitySeed;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.tenant.domain.OrgStatus;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 匿名公开目录验收（J-02）。
 *
 * <h2>这个类在防什么</h2>
 * 公开面一旦做松，损失和其它模块不同：**它没有身份，所以没有"是谁"可追**。
 * <ul>
 *   <li>把内部 UUID 放进响应——全网爬虫就能按标识扫；</li>
 *   <li>把总房数/已售数放出去——竞争对手每天拉一次就是一份市场情报；</li>
 *   <li>把"不存在"和"已停用"区分开——等于提供一个门店存在性探测接口；</li>
 *   <li>日期区间不设上限——一次请求就能把一年房态拉走。</li>
 * </ul>
 * 每一条都只能靠断言响应体发现。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "pms.public-api.enabled=true")
class PublicCatalogApiTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private IdentityMapper identityMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RoomService roomService;

    // ==================== 可访问性 ====================

    @Test
    void anonymousRequestSucceedsWithoutToken() throws Exception {
        // 这是"匿名面"的存在证明：不带 Authorization 也能拿到目录。
        // 如果哪天它开始要令牌，说明被并进了租户面，客户端在登录前将什么都看不到。
        IdentitySeed.Seed seed = seed("pc1");
        createRoomType(seed, "STD", "标准间");

        mockMvc.perform(get(catalogUrl(seed))
                        .param("from", tomorrow())
                        .param("to", dayAfterTomorrow()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantCode").value(seed.tenantCode()))
                .andExpect(jsonPath("$.hotel.code").value("h" + "pc1"))
                .andExpect(jsonPath("$.nights").value(1));
    }

    @Test
    void responseExposesOnlyPublicFields() throws Exception {
        // 字段清单就是"允许外泄的全部"。总房数/已售数/内部 id 一个都不能在。
        IdentitySeed.Seed seed = seed("pc2");
        RoomType roomType = createRoomType(seed, "STD", "标准间");
        roomService.createRoom(seed.tenantId(), seed.hotelId(), roomType.id(), "0901", 9, null);

        String body = mockMvc.perform(get(catalogUrl(seed))
                        .param("from", tomorrow())
                        .param("to", dayAfterTomorrow()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 内部标识与经营数据：出现任意一个都是泄漏
        for (String forbidden : new String[]{
                "roomTypeId", "hotelId", "tenantId\"", "totalRooms", "soldRooms", "blockedRooms",
                roomType.id().toString(), seed.hotelId().toString()}) {
            Assertions.assertFalse(body.contains(forbidden),
                    "响应里出现了不该外泄的内容：" + forbidden);
        }

        // 更严格的一条：整个响应里不该出现任何名为 id 的字段
        Assertions.assertFalse(body.contains("\"id\":"),
                "响应里出现了 id 字段；公开面不应暴露任何内部标识");

        // 该给的必须有：编码、名称、容量、是否可售
        Assertions.assertTrue(body.contains("\"code\":\"std\""), "缺少房型编码（编码已归一为小写）");
        Assertions.assertTrue(body.contains("\"bookable\":true"), "有实体房却未标记可售");
        Assertions.assertTrue(body.contains("\"timezone\""), "缺少时区（客户端据此算今天的边界）");
    }

    @Test
    void bookableFalseWhenNoPhysicalRoom() throws Exception {
        // 只建房型不建房间：该房型在库里存在，但一间都卖不了。
        // 客户端要能区分"有这个房型"与"现在能订"，所以不能把不可售的直接过滤掉。
        IdentitySeed.Seed seed = seed("pc3");
        createRoomType(seed, "DLX", "豪华间");

        mockMvc.perform(get(catalogUrl(seed))
                        .param("from", tomorrow())
                        .param("to", dayAfterTomorrow()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomTypes.length()").value(1))
                // 房型编码在服务层归一为小写，公开面沿用库内值，不再另做一次格式变换
                .andExpect(jsonPath("$.roomTypes[0].code").value("dlx"))
                .andExpect(jsonPath("$.roomTypes[0].bookable").value(false));
    }

    @Test
    void inactiveRoomTypeIsHidden() throws Exception {
        // 停用房型在商家端的语义是"暂不售卖"，公开面不该继续展示。
        IdentitySeed.Seed seed = seed("pc4");
        RoomType roomType = createRoomType(seed, "OLD", "旧房型");
        // 不建实体房：房型下还有在用房间时不允许停用（RoomService 的规则，与公开面无关）。
        roomService.changeRoomTypeStatus(seed.tenantId(), seed.hotelId(), roomType.id(),
                OrgStatus.INACTIVE);

        mockMvc.perform(get(catalogUrl(seed))
                        .param("from", tomorrow())
                        .param("to", dayAfterTomorrow()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomTypes.length()").value(0));
    }

    // ==================== 不泄露存在性 ====================

    @Test
    void unknownTenantAndUnknownHotelAreIndistinguishable() throws Exception {
        // 区分"租户不存在"与"门店不存在"，就等于提供了一个探测接口。
        // 两者必须是同一状态码 + 同一句话。
        IdentitySeed.Seed seed = seed("pc5");
        createRoomType(seed, "STD", "标准间");

        String wrongTenant = mockMvc.perform(get("/api/v1/public/no-such-tenant/hotels/hpc5/room-types")
                        .param("from", tomorrow())
                        .param("to", dayAfterTomorrow()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        String wrongHotel = mockMvc.perform(get("/api/v1/public/" + seed.tenantCode()
                        + "/hotels/no-such-hotel/room-types")
                        .param("from", tomorrow())
                        .param("to", dayAfterTomorrow()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        Assertions.assertEquals(extractDetail(wrongTenant), extractDetail(wrongHotel),
                "租户不存在与门店不存在的文案必须一致，否则可用于探测");
    }

    // ==================== 参数边界 ====================

    @Test
    void windowLongerThan62DaysIsRejected() throws Exception {
        // 不设上限的话，一次请求就能把整年房态拉走。
        IdentitySeed.Seed seed = seed("pc6");
        createRoomType(seed, "STD", "标准间");

        mockMvc.perform(get(catalogUrl(seed))
                        .param("from", "2027-01-01")
                        .param("to", "2027-12-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("最多")));
    }

    @Test
    void endDateNotAfterStartIsRejected() throws Exception {
        IdentitySeed.Seed seed = seed("pc7");
        createRoomType(seed, "STD", "标准间");

        mockMvc.perform(get(catalogUrl(seed))
                        .param("from", "2027-03-10")
                        .param("to", "2027-03-10"))
                .andExpect(status().isBadRequest());
    }

    // ==================== 辅助 ====================

    private IdentitySeed.Seed seed(String suffix) {
        return IdentitySeed.seed(organizationService, identityMapper, passwordEncoder, suffix,
                new String[]{"room:read"}, true);
    }

    private RoomType createRoomType(IdentitySeed.Seed seed, String code, String name) {
        return roomService.createRoomType(seed.tenantId(), seed.hotelId(), code, name, 2, 0, "TWIN");
    }

    /** 夹具门店编码是 {@code h + suffix}；租户编码是 {@code t- + suffix}。 */
    private String catalogUrl(IdentitySeed.Seed seed) {
        return "/api/v1/public/" + seed.tenantCode() + "/hotels/h" + seed.tenantCode().substring(2)
                + "/room-types";
    }

    private static String tomorrow() {
        return LocalDate.now().plusDays(1).toString();
    }

    private static String dayAfterTomorrow() {
        return LocalDate.now().plusDays(2).toString();
    }

    /**
     * 取 ProblemDetail 的 detail 值。
     *
     * <p>不能从 {@code "detail"} 一路截到结尾——那会把 {@code instance} 与 {@code requestId}
     * 一起比进来，而这两者本来就每次都不同，断言必然失败。
     */
    private static String extractDetail(String problemJson) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"detail\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(problemJson);
        return matcher.find() ? matcher.group(1) : problemJson;
    }
}
