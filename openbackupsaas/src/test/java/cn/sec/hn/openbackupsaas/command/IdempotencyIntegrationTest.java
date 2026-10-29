package cn.sec.hn.openbackupsaas.command;

import cn.sec.hn.openbackupsaas.command.domain.IdempotencyStatus;
import cn.sec.hn.openbackupsaas.command.domain.RequestDigest;
import cn.sec.hn.openbackupsaas.command.service.IdempotencyService;
import cn.sec.hn.openbackupsaas.reservation.domain.Reservation;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationLine;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.RoomOccupancy;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationMapper;
import cn.sec.hn.openbackupsaas.reservation.service.OccupancyService;
import cn.sec.hn.openbackupsaas.room.domain.PhysicalRoom;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-07 幂等集成测试：**必须连真实 PostgreSQL**。
 *
 * <p>运行方式：
 * <pre>
 *   ./scripts/dev-db.sh start
 *   PMS_IT_DB=true ./mvnw -B test -Dtest=IdempotencyIntegrationTest
 * </pre>
 *
 * <p>对应四条验收：同键同内容返回原结果、同键不同内容拒绝、并发同键只一次效果、
 * 提交成功但响应丢失可恢复。最后一条用"执行后按 operationId 查回结果引用"来证明。
 */
@SpringBootTest
@ActiveProfiles("integration")
@EnabledIfEnvironmentVariable(named = "PMS_IT_DB", matches = "true")
@Sql(scripts = "/db/integration-cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class IdempotencyIntegrationTest {

    private static final String NAMESPACE = "employee";
    private static final String COMMAND = "reservation.hold";
    private static final LocalDate IN = LocalDate.of(2026, 5, 1);
    private static final LocalDate OUT = LocalDate.of(2026, 5, 4);

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private OccupancyService occupancyService;

    @Autowired
    private ReservationMapper reservationMapper;

    @Autowired
    private RoomService roomService;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void sameKeySamePayloadReplaysOriginalResult() {
        Fixture fixture = fixture("i1");
        IdempotencyService.CommandRequest request = request(fixture, "key-same", payload(fixture));

        IdempotencyService.CommandResult<RoomOccupancy> first = idempotencyService.execute(request, operationId -> hold(fixture, operationId));
        IdempotencyService.CommandResult<RoomOccupancy> second = idempotencyService.execute(request, operationId -> hold(fixture, operationId));

        assertEquals(false, first.replayed(), "首次执行不该是重放");
        assertTrue(second.replayed(), "第二次必须是重放，而不是再执行一次业务");
        assertEquals(first.resultRef(), second.resultRef(), "重放必须返回同一个业务结果引用");
        assertEquals(1, count("room_occupancy"), "业务只能发生一次");
        assertEquals(3, count("inventory_ledger"), "流水也只能有一份（三晚）");
    }

    @Test
    void sameKeyDifferentPayloadIsRejected() {
        Fixture fixture = fixture("i2");
        IdempotencyService.CommandRequest first = request(fixture, "key-diff", payload(fixture));
        idempotencyService.execute(first, operationId -> hold(fixture, operationId));

        Map<String, Object> changed = payload(fixture);
        changed.put("from", "2026-05-02");
        IdempotencyService.CommandRequest second = request(fixture, "key-diff", changed);

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> idempotencyService.execute(second, operationId -> hold(fixture, operationId)));
        assertTrue(conflict.getMessage().contains("不同的请求内容"), "实际=" + conflict.getMessage());
        assertEquals(1, count("room_occupancy"), "拒绝的那次不能留下任何业务效果");
    }

    @Test
    void payloadFieldOrderDoesNotBreakTheDigest() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("roomId", "r1");
        a.put("from", "2026-05-01");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("from", "2026-05-01");
        b.put("roomId", "r1");

        assertEquals(RequestDigest.sha256Hex(a), RequestDigest.sha256Hex(b),
                "字段顺序不同必须算出同一摘要，否则幂等会被序列化差异击穿");
        assertNotEquals(RequestDigest.sha256Hex(a), RequestDigest.sha256Hex(Map.of("roomId", "r2")));
    }

    @Test
    void concurrentSameKeyProducesOneEffectOnly() throws Exception {
        Fixture fixture = fixture("i3");
        IdempotencyService.CommandRequest request = request(fixture, "key-race", payload(fixture));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> attempt = () -> {
                try {
                    IdempotencyService.CommandResult<RoomOccupancy> result =
                            idempotencyService.execute(request, operationId -> hold(fixture, operationId));
                    return result.replayed() ? "replayed" : "executed";
                } catch (ConflictException ex) {
                    return "rejected";
                }
            };
            Future<String> one = pool.submit(attempt);
            Future<String> two = pool.submit(attempt);
            String r1 = one.get(30, TimeUnit.SECONDS);
            String r2 = two.get(30, TimeUnit.SECONDS);

            assertEquals(1, java.util.List.of(r1, r2).stream().filter(v -> v.equals("executed")).count(),
                    "两个并发请求里必须恰好一个真正执行（实际=" + r1 + ", " + r2 + "）");
            assertEquals(1, count("room_occupancy"), "并发同键只产生一次效果");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void committedButResponseLostCanBeRecovered() {
        Fixture fixture = fixture("i4");
        IdempotencyService.CommandRequest request = request(fixture, "key-lost", payload(fixture));
        IdempotencyService.CommandResult<RoomOccupancy> first = idempotencyService.execute(request, operationId -> hold(fixture, operationId));

        // 模拟"响应丢了"：客户端只记得 operationId
        var stored = idempotencyService.requireOperation(fixture.tenantId(), first.operationId());
        assertEquals(IdempotencyStatus.SUCCEEDED, stored.status(), "提交成功必须留下可查的成功记录");
        assertEquals(first.resultRef(), stored.resultRef(), "结果引用必须能还原原结果");

        // 客户端拿着原键重试：拿到同一个结果引用，业务没有第二次发生
        IdempotencyService.CommandResult<RoomOccupancy> retry = idempotencyService.execute(request, operationId -> hold(fixture, operationId));
        assertTrue(retry.replayed());
        assertEquals(first.resultRef(), retry.resultRef());
        assertEquals(1, count("room_occupancy"));
    }

    @Test
    void operationLookupIsTenantScoped() {
        Fixture mine = fixture("i5");
        Fixture other = fixture("i6");
        IdempotencyService.CommandResult<RoomOccupancy> result =
                idempotencyService.execute(request(mine, "key-scope", payload(mine)), operationId -> hold(mine, operationId));

        assertThrows(NotFoundException.class, () -> idempotencyService.requireOperation(
                other.tenantId(), result.operationId()), "别的租户查不到就是 404，不解释存在性");
    }

    @Test
    void rejectedCommandIsRecordedAndReplayRefused() {
        Fixture fixture = fixture("i7");
        IdempotencyService.CommandRequest request = request(fixture, "key-reject", payload(fixture));

        idempotencyService.recordRejected(request, "ROOM_TYPE_INACTIVE");

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> idempotencyService.execute(request, operationId -> hold(fixture, operationId)));
        assertTrue(conflict.getMessage().contains("已失败"), "实际=" + conflict.getMessage());
        assertEquals(0, count("room_occupancy"), "被拒绝的命令不能产生任何效果");
    }

    @Test
    void ledgerOperationMustReferenceARealOperation() {
        Fixture fixture = fixture("i8");
        idempotencyService.execute(request(fixture, "key-fk", payload(fixture)), operationId -> hold(fixture, operationId));

        // 绕过服务直接插一条虚构 operation_id 的流水：外键必须挡住
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO inventory_ledger (id, tenant_id, hotel_id, room_id, stay_date, effect_kind,"
                                + " operation_id, delta, reason_code, effective_from_date, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, 'RESERVATION', ?, -1, '虚构', ?, now())",
                        UUID.randomUUID(), fixture.tenantId(), fixture.hotelId(), fixture.roomId(),
                        LocalDate.of(2026, 5, 1), UUID.randomUUID(), LocalDate.of(2026, 5, 1)),
                "fk_inventory_ledger_operation 必须挡住虚构的 operation_id");
    }

    private IdempotencyService.CommandOutcome<RoomOccupancy> hold(Fixture fixture, UUID operationId) {
        RoomOccupancy occupancy = occupancyService.hold(fixture.tenantId(), fixture.hotelId(),
                fixture.reservationId(), fixture.lineId(), fixture.roomId(), StayRange.of(IN, OUT),
                OffsetDateTime.now().plusMinutes(30), "幂等命令");
        return new IdempotencyService.CommandOutcome<>(occupancy, occupancy.id(),
                Map.of("occupancyId", occupancy.id()));
    }

    private IdempotencyService.CommandRequest request(Fixture fixture, String key, Map<String, Object> payload) {
        return new IdempotencyService.CommandRequest(fixture.tenantId(), fixture.hotelId(), NAMESPACE, COMMAND,
                key, payload);
    }

    private Map<String, Object> payload(Fixture fixture) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roomId", fixture.roomId());
        payload.put("lineId", fixture.lineId());
        payload.put("from", IN.toString());
        payload.put("to", OUT.toString());
        return payload;
    }

    private int count(String table) {
        Integer value = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private Fixture fixture(String suffix) {
        Tenant tenant = organizationService.createTenant("t-" + suffix, "幂等测试租户 " + suffix);
        Hotel hotel = organizationService.createHotel(tenant.id(), null, "h" + suffix, "主店", "Asia/Shanghai");
        RoomType type = roomService.createRoomType(tenant.id(), hotel.id(), "std", "标准间", 2, 0, null);
        PhysicalRoom room = roomService.createRoom(tenant.id(), hotel.id(), type.id(), "0801", 8, null);

        OffsetDateTime now = OffsetDateTime.now();
        Reservation reservation = Reservation.create(UUID.randomUUID(), tenant.id(), hotel.id(),
                "R" + suffix, ReservationSourceType.FRONTDESK, now);
        reservationMapper.insertReservation(reservation);
        ReservationLine line = ReservationLine.create(UUID.randomUUID(), tenant.id(), hotel.id(),
                reservation.id(), 1, type.id(), StayRange.of(IN, OUT), now);
        reservationMapper.insertLine(line);

        return new Fixture(tenant.id(), hotel.id(), reservation.id(), line.id(), room.id());
    }

    private record Fixture(UUID tenantId, UUID hotelId, UUID reservationId, UUID lineId, UUID roomId) {
    }
}
