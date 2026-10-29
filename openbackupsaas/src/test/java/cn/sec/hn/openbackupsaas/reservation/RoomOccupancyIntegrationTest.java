package cn.sec.hn.openbackupsaas.reservation;

import cn.sec.hn.openbackupsaas.reservation.domain.OccupancyStatus;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-05 占用排斥集成测试：**必须连真实 PostgreSQL**。
 *
 * <p>运行方式：
 * <pre>
 *   ./scripts/dev-db.sh start
 *   PMS_IT_DB=true ./mvnw -B test -Dtest=RoomOccupancyIntegrationTest
 * </pre>
 *
 * <p>要证明的不是"服务能写"，而是 **谁都绕不过**：
 * 相邻区间允许、重叠区间被拒、预订与维修互斥、终态释放后可再销、
 * **直接连库插入同样被拒**、并发下只有一个赢家。
 */
@SpringBootTest
@ActiveProfiles("integration")
@EnabledIfEnvironmentVariable(named = "PMS_IT_DB", matches = "true")
@Sql(scripts = "/db/integration-cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class RoomOccupancyIntegrationTest {

    private static final LocalDate IN = LocalDate.of(2026, 3, 1);
    private static final LocalDate OUT = LocalDate.of(2026, 3, 5);

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void adjacentRangesAreAllowed() {
        Fixture fixture = fixture("a1");

        occupancyService.hold(fixture.tenantId(), fixture.hotelId(), fixture.reservationId(),
                fixture.lineId(), fixture.roomId(), StayRange.of(IN, OUT), expiry(), "第一单");
        RoomOccupancy second = occupancyService.hold(fixture.tenantId(), fixture.hotelId(),
                fixture.reservationId(), fixture.secondLineId(), fixture.roomId(),
                StayRange.of(OUT, OUT.plusDays(3)), expiry(), "第二单");

        assertEquals(2, occupancyService.occupanciesOfRoom(fixture.tenantId(), fixture.hotelId(),
                fixture.roomId()).size(), "退房日=入住日的相邻区间必须允许");
        assertTrue(StayRange.of(OUT, OUT.plusDays(3)).isAdjacentTo(StayRange.of(IN, OUT)));
        assertEquals(OccupancyStatus.HELD, second.status());
    }

    @Test
    void overlappingStayIsRejectedAndTranslatedToBusinessConflict() {
        Fixture fixture = fixture("a2");
        occupancyService.hold(fixture.tenantId(), fixture.hotelId(), fixture.reservationId(),
                fixture.lineId(), fixture.roomId(), StayRange.of(IN, OUT), expiry(), "先到");

        ConflictException conflict = assertThrows(ConflictException.class, () -> occupancyService.hold(
                fixture.tenantId(), fixture.hotelId(), fixture.reservationId(), fixture.secondLineId(),
                fixture.roomId(), StayRange.of(IN.plusDays(2), OUT.plusDays(2)), expiry(), "后到"));

        assertTrue(conflict.getMessage().contains("已被占用"),
                "冲突文案必须说清是《房间被占》，而不是《数据冲突》：实际=" + conflict.getMessage());
        assertEquals(1, countOccupancies(fixture), "失败的那次不能留下任何占用");
    }

    @Test
    void reservationAndMaintenanceCannotHoldTheSameRange() {
        Fixture fixture = fixture("a3");
        occupancyService.hold(fixture.tenantId(), fixture.hotelId(), fixture.reservationId(),
                fixture.lineId(), fixture.roomId(), StayRange.of(IN, OUT), expiry(), "预订");

        ConflictException conflict = assertThrows(ConflictException.class, () -> occupancyService.block(
                fixture.tenantId(), fixture.hotelId(), fixture.roomId(),
                StayRange.of(IN.plusDays(1), OUT.minusDays(1)), "维修"));

        assertTrue(conflict.getMessage().contains("已被占用"));
        assertEquals(1, countOccupancies(fixture), "维修不能与预订同时拿到冲突区间");
    }

    @Test
    void releasedOccupancyFreesTheDates() {
        Fixture fixture = fixture("a4");
        RoomOccupancy held = occupancyService.hold(fixture.tenantId(), fixture.hotelId(),
                fixture.reservationId(), fixture.lineId(), fixture.roomId(),
                StayRange.of(IN, OUT), expiry(), "先占");

        occupancyService.changeStatus(fixture.tenantId(), fixture.hotelId(), held.id(), OccupancyStatus.RELEASED);

        RoomOccupancy again = occupancyService.hold(fixture.tenantId(), fixture.hotelId(),
                fixture.reservationId(), fixture.secondLineId(), fixture.roomId(),
                StayRange.of(IN, OUT), expiry(), "释放后再占");

        assertEquals(OccupancyStatus.HELD, again.status());
        assertEquals(2, countOccupancies(fixture), "终态不删行：历史占用要留着对账");
    }

    @Test
    void unblockedMaintenanceFreesTheDates() {
        Fixture fixture = fixture("a5");
        RoomOccupancy blocked = occupancyService.block(fixture.tenantId(), fixture.hotelId(),
                fixture.roomId(), StayRange.of(IN, OUT), "维修");

        occupancyService.changeStatus(fixture.tenantId(), fixture.hotelId(), blocked.id(),
                OccupancyStatus.UNBLOCKED);

        assertTrue(occupancyService.hold(fixture.tenantId(), fixture.hotelId(), fixture.reservationId(),
                fixture.lineId(), fixture.roomId(), StayRange.of(IN, OUT), expiry(), "解除后入住") != null);
    }

    @Test
    void directDatabaseInsertCannotBypassExclusion() {
        Fixture fixture = fixture("a6");
        occupancyService.hold(fixture.tenantId(), fixture.hotelId(), fixture.reservationId(),
                fixture.lineId(), fixture.roomId(), StayRange.of(IN, OUT), expiry(), "先到");

        // 绕过服务与 Mapper，直接用 SQL 插一行重叠占用：仍必须失败
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO room_occupancy (id, tenant_id, hotel_id, room_id, kind, reservation_id, line_id,"
                        + " stay_range, status, expires_at, reason_code, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'RESERVATION', ?, ?, daterange('2026-03-03','2026-03-08'),"
                        + " 'HELD', ?, '直连插入', 0, now(), now())",
                UUID.randomUUID(), fixture.tenantId(), fixture.hotelId(), fixture.roomId(),
                fixture.reservationId(), fixture.secondLineId(),
                OffsetDateTime.now().plusMinutes(30)),
                "ex_room_occupancy_effective 必须挡住直接连库的插入");

        assertEquals(1, countOccupancies(fixture));
    }

    @Test
    void invalidRangesRejectedByDatabase() {
        Fixture fixture = fixture("a7");

        assertThrows(DataIntegrityViolationException.class, () -> insertRawOccupancy(fixture,
                "'empty'::daterange", "HELD"), "空区间必须被拒");
        assertThrows(DataIntegrityViolationException.class, () -> insertRawOccupancy(fixture,
                "'(,)'::daterange", "HELD"), "无界区间必须被拒");
        // 注意：PostgreSQL 会把 [] 规范化为 [)，因此"闭区间"无法存进 daterange——
        // 这不是我们的 CHECK 起作用，而是类型本身的保证，这里验证的是"反向区间"必须被拒。
        assertThrows(DataIntegrityViolationException.class, () -> insertRawOccupancy(fixture,
                "daterange('2026-03-12','2026-03-10')", "HELD"), "反向区间必须被拒");
        assertEquals(0, countOccupancies(fixture));
    }

    @Test
    void kindAndReferenceMustBeConsistent() {
        Fixture fixture = fixture("a8");

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO room_occupancy (id, tenant_id, hotel_id, room_id, kind, reservation_id, line_id,"
                        + " stay_range, status, reason_code, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'BLOCK', ?, ?, daterange('2026-03-01','2026-03-05'),"
                        + " 'BLOCKED', '封房', 0, now(), now())",
                UUID.randomUUID(), fixture.tenantId(), fixture.hotelId(), fixture.roomId(),
                fixture.reservationId(), fixture.lineId()),
                "封房不得带订单引用");

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO room_occupancy (id, tenant_id, hotel_id, room_id, kind, stay_range, status,"
                        + " expires_at, reason_code, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'RESERVATION', daterange('2026-03-01','2026-03-05'),"
                        + " 'HELD', ?, '缺订单', 0, now(), now())",
                UUID.randomUUID(), fixture.tenantId(), fixture.hotelId(), fixture.roomId(),
                OffsetDateTime.now().plusMinutes(30)),
                "预订占用必须带订单与订单行");
    }

    @Test
    void heldMustCarryAnExpiry() {
        Fixture fixture = fixture("a9");

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO room_occupancy (id, tenant_id, hotel_id, room_id, kind, reservation_id, line_id,"
                        + " stay_range, status, reason_code, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'RESERVATION', ?, ?, daterange('2026-03-01','2026-03-05'),"
                        + " 'HELD', '无截止', 0, now(), now())",
                UUID.randomUUID(), fixture.tenantId(), fixture.hotelId(), fixture.roomId(),
                fixture.reservationId(), fixture.lineId()),
                "HELD 没有截止点会让占位无限期占用房间");
    }

    @Test
    void oneLineKeepsAtMostOneEffectiveOccupancy() {
        Fixture fixture = fixture("b1");

        occupancyService.hold(fixture.tenantId(), fixture.hotelId(), fixture.reservationId(),
                fixture.lineId(), fixture.roomId(), StayRange.of(IN, OUT), expiry(), "第一间");

        // 同一订单行换到另一间房、不重叠的日期，也必须有且只有一个有效占用
        assertThrows(ConflictException.class, () -> occupancyService.hold(fixture.tenantId(), fixture.hotelId(),
                fixture.reservationId(), fixture.lineId(), fixture.secondRoomId(),
                StayRange.of(IN.plusDays(10), IN.plusDays(12)), expiry(), "换房"));

        assertEquals(1, countOccupancies(fixture));
    }

    @Test
    void occupancyCannotPointToRoomOfAnotherHotel() {
        Fixture fixture = fixture("b2");

        // 锁房阶段就查不到（房间不属于该门店），因此是 404 而不是 409：
        // 用 409 等于告诉调用方"这个房间 id 确实存在于别处"
        assertThrows(NotFoundException.class, () -> occupancyService.hold(
                fixture.tenantId(), fixture.hotelId(), fixture.reservationId(), fixture.lineId(),
                fixture.roomOfSecondHotel(), StayRange.of(IN, OUT), expiry(), "跨店"));

        // 绕过服务直接用 Mapper 插：复合外键必须挡住
        RoomOccupancy forged = RoomOccupancy.held(UUID.randomUUID(), fixture.tenantId(), fixture.hotelId(),
                fixture.roomOfSecondHotel(), fixture.reservationId(), fixture.lineId(),
                StayRange.of(IN, OUT), expiry(), "跨店直插", OffsetDateTime.now());
        assertThrows(DataIntegrityViolationException.class, () -> reservationMapper.insertOccupancy(forged),
                "fk_room_occupancy_room 必须挡住跨门店房间引用");
    }

    @Test
    void concurrentOverlappingHoldsLeaveExactlyOneWinner() throws Exception {
        Fixture fixture = fixture("b3");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> first = () -> attemptHold(fixture, StayRange.of(IN, OUT), fixture.lineId());
            Callable<Boolean> second = () -> attemptHold(fixture, StayRange.of(IN.plusDays(1), OUT.plusDays(1)),
                    fixture.secondLineId());

            Future<Boolean> f1 = pool.submit(first);
            Future<Boolean> f2 = pool.submit(second);

            boolean r1 = f1.get(30, TimeUnit.SECONDS);
            boolean r2 = f2.get(30, TimeUnit.SECONDS);

            assertEquals(1, (r1 ? 1 : 0) + (r2 ? 1 : 0),
                    "两个事务抢同一间房的冲突区间，必须只有一个成功（r1=" + r1 + ", r2=" + r2 + "）");
            assertEquals(1, countOccupancies(fixture));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lockedRoomsAreReturnedInAscendingIdOrder() {
        Fixture fixture = fixture("b4");

        // 传入顺序故意倒序：锁序由 SQL 决定，不能让调用方影响
        List<UUID> locked = reservationMapper.lockRooms(fixture.tenantId(), fixture.hotelId(),
                new ArrayList<>(List.of(fixture.secondRoomId(), fixture.roomId())))
                .stream()
                .map(PhysicalRoom::id)
                .toList();

        assertEquals(2, locked.size());
        // 按十六进制文本比较，而不是 UUID.compareTo：后者按 mostSigBits **有符号**比较，
        // 与 PostgreSQL 的 uuid 排序（按字节）不一致，会在高位为 1 的 uuid 上得出相反结论。
        assertTrue(locked.get(0).toString().compareTo(locked.get(1).toString()) < 0,
                "资源锁必须按 id 升序，避免死锁：实际=" + locked);
    }

    private boolean attemptHold(Fixture fixture, StayRange range, UUID lineId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        try {
            Boolean done = template.execute(status -> {
                occupancyService.hold(fixture.tenantId(), fixture.hotelId(), fixture.reservationId(),
                        lineId, fixture.roomId(), range, expiry(), "并发");
                return Boolean.TRUE;
            });
            return Boolean.TRUE.equals(done);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void insertRawOccupancy(Fixture fixture, String rangeExpression, String status) {
        jdbcTemplate.update(
                "INSERT INTO room_occupancy (id, tenant_id, hotel_id, room_id, kind, reservation_id, line_id,"
                        + " stay_range, status, expires_at, reason_code, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'RESERVATION', ?, ?, " + rangeExpression + ", ?, ?, '非法区间', 0, now(), now())",
                UUID.randomUUID(), fixture.tenantId(), fixture.hotelId(), fixture.roomId(),
                fixture.reservationId(), fixture.lineId(), status, OffsetDateTime.now().plusMinutes(30));
    }

    private int countOccupancies(Fixture fixture) {
        return occupancyService.occupanciesOfRoom(fixture.tenantId(), fixture.hotelId(), fixture.roomId()).size();
    }

    private static OffsetDateTime expiry() {
        return OffsetDateTime.now().plusMinutes(30);
    }

    private Fixture fixture(String suffix) {
        Tenant tenant = organizationService.createTenant("t-" + suffix, "占用测试租户 " + suffix);
        Hotel hotel = organizationService.createHotel(tenant.id(), null, "h" + suffix, "主店", "Asia/Shanghai");
        Hotel secondHotel = organizationService.createHotel(tenant.id(), null, "s" + suffix, "次店", "Asia/Shanghai");
        RoomType type = roomService.createRoomType(tenant.id(), hotel.id(), "std", "标准间", 2, 0, null);
        PhysicalRoom room = roomService.createRoom(tenant.id(), hotel.id(), type.id(), "0801", 8, null);
        PhysicalRoom secondRoom = roomService.createRoom(tenant.id(), hotel.id(), type.id(), "0802", 8, null);
        RoomType otherHotelType = roomService.createRoomType(tenant.id(), secondHotel.id(), "std", "标准间", 2, 0, null);
        PhysicalRoom otherHotelRoom = roomService.createRoom(tenant.id(), secondHotel.id(), otherHotelType.id(),
                "0901", 9, null);

        OffsetDateTime now = OffsetDateTime.now();
        Reservation reservation = Reservation.create(UUID.randomUUID(), tenant.id(), hotel.id(),
                "R" + suffix, ReservationSourceType.FRONTDESK, now);
        reservationMapper.insertReservation(reservation);
        ReservationLine firstLine = ReservationLine.create(UUID.randomUUID(), tenant.id(), hotel.id(),
                reservation.id(), 1, type.id(), StayRange.of(IN, OUT), now);
        ReservationLine secondLine = ReservationLine.create(UUID.randomUUID(), tenant.id(), hotel.id(),
                reservation.id(), 2, type.id(), StayRange.of(IN, OUT), now);
        reservationMapper.insertLine(firstLine);
        reservationMapper.insertLine(secondLine);

        return new Fixture(tenant.id(), hotel.id(), reservation.id(), firstLine.id(), secondLine.id(),
                room.id(), secondRoom.id(), otherHotelRoom.id());
    }

    /** 夹具：一间门店下两间房 + 一间"另一家门店"的房间 + 一个订单两个行。 */
    private record Fixture(UUID tenantId, UUID hotelId, UUID reservationId, UUID lineId, UUID secondLineId,
                           UUID roomId, UUID secondRoomId, UUID roomOfSecondHotel) {
    }
}
