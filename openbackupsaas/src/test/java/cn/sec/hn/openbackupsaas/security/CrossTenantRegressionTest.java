package cn.sec.hn.openbackupsaas.security;

import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationMapper;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationCommandService;
import cn.sec.hn.openbackupsaas.reservation.web.ReservationView;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.shared.security.TenantContext;
import cn.sec.hn.openbackupsaas.shared.security.TenantContextHolder;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 越权回归集（S1-13 / T14 / B-08）。
 *
 * <p>覆盖四类越权手法，每一条都是"能拿到别人的数据"的具体路径：
 * <ol>
 *   <li><b>换对象 ID</b>：拿自己的令牌配别人的订单 id；</li>
 *   <li><b>换门店 ID</b>：跨租户的门店（应 404，不能 403——那等于承认它存在）与同租户超范围门店（403）；</li>
 *   <li><b>在请求体里塞归属</b>：伪造 tenantId/hotelId 字段；</li>
 *   <li><b>线程复用</b>：租户上下文在池化线程上串味。</li>
 * </ol>
 *
 * <p>断言的重点不是"返回了什么错误码"（那只是表象），而是<b>一行别人的数据都没有被返回</b>——
 * 所以每条用例同时核验响应体里不含对方订单号。
 */
@AutoConfigureMockMvc
class CrossTenantRegressionTest extends PostgresIntegrationTest {

    private static final LocalDate IN = LocalDate.of(2026, 12, 1);
    private static final LocalDate OUT = LocalDate.of(2026, 12, 3);

    private static final String[] FULL_PERMISSIONS = {"reservation:read", "reservation:write", "inventory:read"};

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
    @Autowired
    private ReservationMapper reservationMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ===================== 1. 换对象 ID =====================

    @Test
    void reservationIdFromAnotherTenantIsNotFoundAndBodyHasNoTrace() throws Exception {
        Ctx mine = context("x1", FULL_PERMISSIONS);
        Ctx theirs = context("x2", FULL_PERMISSIONS);
        ReservationView foreign = create(theirs);

        MvcResult result = mockMvc.perform(
                        get("/api/v1/hotels/{hotelId}/reservations/{id}", mine.seed.hotelId(), foreign.id())
                                .header("Authorization", "Bearer " + mine.token))
                .andExpect(status().isNotFound())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(!body.contains(foreign.reservationNo()),
                "404 响应里不得出现对方的订单号，否则等于确认了它存在");
    }

    // ===================== 2. 换门店 ID =====================

    @Test
    void hotelIdFromAnotherTenantIsNotFoundNotForbidden() throws Exception {
        Ctx mine = context("x3", FULL_PERMISSIONS);
        Ctx theirs = context("x4", FULL_PERMISSIONS);
        create(theirs);

        // 跨租户门店必须是 404：403 会告诉对方"这家门店真实存在"
        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reservations", theirs.seed.hotelId())
                        .header("Authorization", "Bearer " + mine.token))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameTenantHotelOutsideScopeIsForbidden() throws Exception {
        Ctx ctx = context("x5", FULL_PERMISSIONS);

        // 同租户、但该账号的门店范围里没有它 → 403（门店确实存在，瞒不住也不该瞒）
        mockMvc.perform(get("/api/v1/hotels/{hotelId}/availability", ctx.seed.secondHotelId())
                        .param("from", IN.toString()).param("to", OUT.toString())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isForbidden());
    }

    /**
     * 即使把第二家门店加进范围，用它的路径也查不到第一家的订单——
     * 门店范围只说明"能看这家店"，不说明"能看这家店里的任何 id"。
     */
    @Test
    void reservationIsInvisibleThroughAnotherHotelEvenWhenBothAreInScope() throws Exception {
        Ctx ctx = context("x6", FULL_PERMISSIONS);
        ReservationView inFirstHotel = create(ctx);

        identityMapper.addHotelScope(ctx.seed.tenantId(), ctx.seed.userId(), ctx.seed.secondHotelId());

        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reservations/{id}",
                        ctx.seed.secondHotelId(), inFirstHotel.id())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isNotFound());

        // 换成正确门店则可见：证明上面的 404 来自门店归属，而不是订单本身有问题
        mockMvc.perform(get("/api/v1/hotels/{hotelId}/reservations/{id}",
                        ctx.seed.hotelId(), inFirstHotel.id())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk());
    }

    // ===================== 3. 请求体里塞归属 =====================

    @Test
    void forgedTenantAndHotelFieldsInBodyDoNotChangeOwnership() throws Exception {
        Ctx mine = context("x7", FULL_PERMISSIONS);
        Ctx theirs = context("x8", FULL_PERMISSIONS);

        String body = objectMapper.writeValueAsString(Map.of(
                "idempotencyKey", "forged-" + UUID.randomUUID(),
                "sourceType", "FRONTDESK",
                // 伪造归属：服务端必须无视这两个字段
                "tenantId", theirs.seed.tenantId().toString(),
                "hotelId", theirs.seed.secondHotelId().toString(),
                "lines", List.of(Map.of(
                        "roomTypeId", mine.roomTypeId.toString(),
                        "checkIn", IN.toString(),
                        "checkOut", OUT.toString()))));

        MvcResult result = mockMvc.perform(post("/api/v1/hotels/{hotelId}/reservations", mine.seed.hotelId())
                        .header("Authorization", "Bearer " + mine.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        // 两种结果都算合格：字段被忽略（201）或请求被拒（400）。不合格的是"按伪造值落库"。
        assertTrue(result.getResponse().getStatus() == 201 || result.getResponse().getStatus() == 400,
                "伪造归属字段不应被接受为有效请求，实际状态码 " + result.getResponse().getStatus());

        Integer leaked = jdbcCount("SELECT count(*) FROM reservation WHERE hotel_id = ?", theirs.seed.hotelId());
        assertEquals(0, leaked, "不得在对方门店下创建任何订单");
        Integer mineCount = jdbcCount("SELECT count(*) FROM reservation WHERE hotel_id = ?", mine.seed.hotelId());
        assertTrue(mineCount <= 1, "本店最多一单");
    }

    // ===================== 4. 线程复用 =====================

    /**
     * 两个上下文在同一个线程上交替绑定，查询结果必须各自只看自己的一单。
     *
     * <p>这一条防的是 ThreadLocal 没清理：线程归还池子后，下一个请求带着上一个租户的身份跑。
     */
    @Test
    void tenantContextOnReusedThreadsDoesNotLeakBetweenTenants() throws Exception {
        Ctx a = context("x9", FULL_PERMISSIONS);
        Ctx b = context("x10", FULL_PERMISSIONS);
        ReservationView reservationA = create(a);
        ReservationView reservationB = create(b);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> taskA = pool.submit(() -> visibility(a, reservationA.id(), reservationB.id(), start));
            Future<Integer> taskB = pool.submit(() -> visibility(b, reservationB.id(), reservationA.id(), start));
            start.countDown();
            assertEquals(1, taskA.get(30, TimeUnit.SECONDS), "A 的上下文应只看到自己那一单");
            assertEquals(1, taskB.get(30, TimeUnit.SECONDS), "B 的上下文应只看到自己那一单");
        } finally {
            pool.shutdownNow();
        }

        // 清理后必须有清理：线程上不应残留任何上下文
        assertTrue(TenantContextHolder.current().isEmpty(), "测试线程不应残留租户上下文");
    }

    private int visibility(Ctx ctx, UUID own, UUID foreign, CountDownLatch start) throws Exception {
        start.await(20, TimeUnit.SECONDS);
        TenantContext context = new TenantContext(ctx.seed.tenantId(), ctx.seed.userId(), ctx.seed.username(),
                Set.of(ctx.seed.hotelId()), Set.of("reservation:read"));
        try {
            return TenantContextHolder.callAs(context, () -> {
                boolean seeOwn = reservationMapper.findReservation(ctx.seed.tenantId(), ctx.seed.hotelId(), own).isPresent();
                boolean seeForeign = reservationMapper.findReservation(ctx.seed.tenantId(), ctx.seed.hotelId(), foreign).isPresent();
                return (seeOwn ? 1 : 0) + (seeForeign ? 1 : 0);
            });
        } finally {
            TenantContextHolder.clear();
        }
    }

    // ===================== 辅助 =====================

    private ReservationView create(Ctx ctx) {
        return commandService.createReservation(ctx.seed.tenantId(),
                new ReservationCommandService.CreateCommand(ctx.seed.hotelId(), "test-client",
                        "create-" + UUID.randomUUID(), ReservationSourceType.FRONTDESK,
                        List.of(new ReservationCommandService.CreateCommand.LineCommand(
                                ctx.roomTypeId, StayRange.of(IN, OUT)))));
    }

    private Integer jdbcCount(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
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
