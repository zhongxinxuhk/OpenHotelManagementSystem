package cn.sec.hn.openbackupsaas.journal;

import cn.sec.hn.openbackupsaas.journal.domain.AuditActions;
import cn.sec.hn.openbackupsaas.journal.domain.AuditLog;
import cn.sec.hn.openbackupsaas.journal.domain.EffectKind;
import cn.sec.hn.openbackupsaas.journal.domain.InventoryLedger;
import cn.sec.hn.openbackupsaas.journal.domain.OutboxEvent;
import cn.sec.hn.openbackupsaas.journal.mapper.JournalMapper;
import cn.sec.hn.openbackupsaas.journal.service.JournalService;
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
import cn.sec.hn.openbackupsaas.shared.security.TenantContext;
import cn.sec.hn.openbackupsaas.shared.security.TenantContextHolder;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-06 流水、审计与事件集成测试：**必须连真实 PostgreSQL**。
 *
 * <p>运行方式：
 * <pre>
 *   ./scripts/dev-db.sh start
 *   PMS_IT_DB=true ./mvnw -B test -Dtest=JournalIntegrationTest
 * </pre>
 *
 * <p>核心要证明的是**原子性**：一次占用写入要同时产生占用、逐晚流水、审计与事件；
 * 只要其中任何一步失败，四者一起回滚——不能出现"占用在、流水不在"这种半截事实。
 */
@SpringBootTest
@ActiveProfiles("integration")
@EnabledIfEnvironmentVariable(named = "PMS_IT_DB", matches = "true")
@Sql(scripts = "/db/integration-cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class JournalIntegrationTest {

    private static final LocalDate IN = LocalDate.of(2026, 4, 1);
    private static final LocalDate OUT = LocalDate.of(2026, 4, 5);

    @Autowired
    private OccupancyService occupancyService;

    @Autowired
    private JournalService journalService;

    @Autowired
    private cn.sec.hn.openbackupsaas.command.service.IdempotencyService idempotencyService;

    @Autowired
    private JournalMapper journalMapper;

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
    void holdWritesOneLedgerRowPerNightPlusAuditAndEvent() {
        Fixture fixture = fixture("j1");

        RoomOccupancy occupancy = occupancyService.hold(fixture.tenantId(), fixture.hotelId(),
                fixture.reservationId(), fixture.lineId(), fixture.roomId(),
                StayRange.of(IN, OUT), expiry(), "四晚");

        List<InventoryLedger> ledger = journalMapper.findLedgerOfRoom(fixture.tenantId(), fixture.hotelId(),
                fixture.roomId());
        assertEquals(4, ledger.size(), "住四晚必须写四行流水，一行一晚");
        assertTrue(ledger.stream().allMatch(row -> row.delta() == -1));
        assertEquals(List.of(IN, IN.plusDays(1), IN.plusDays(2), IN.plusDays(3)),
                ledger.stream().map(InventoryLedger::stayDate).toList());
        assertEquals(EffectKind.RESERVATION, ledger.get(0).effectKind());

        List<AuditLog> audits = journalMapper.findAuditOfTarget(fixture.tenantId(), "room_occupancy",
                occupancy.id());
        assertEquals(1, audits.size());
        assertEquals(AuditActions.OCCUPANCY_HELD, audits.get(0).action());

        List<OutboxEvent> events = journalMapper.findEventsOfAggregate(fixture.tenantId(),
                JournalService.occupancyAggregate(), occupancy.id());
        assertEquals(1, events.size());
        assertEquals("RoomOccupancyHeld", events.get(0).eventType());
        assertEquals(1, events.get(0).eventOrdinal());
        assertTrue(events.get(0).payload().contains("2026-04-01"), "载荷应含区间，便于订阅方自洽");
    }

    @Test
    void releaseWritesCompensatingRowsWithoutDeletingHistory() {
        Fixture fixture = fixture("j2");
        RoomOccupancy occupancy = occupancyService.hold(fixture.tenantId(), fixture.hotelId(),
                fixture.reservationId(), fixture.lineId(), fixture.roomId(),
                StayRange.of(IN, OUT), expiry(), "先占");

        occupancyService.changeStatus(fixture.tenantId(), fixture.hotelId(), occupancy.id(),
                OccupancyStatus.RELEASED);

        List<InventoryLedger> ledger = journalMapper.findLedgerOfRoom(fixture.tenantId(), fixture.hotelId(),
                fixture.roomId());
        assertEquals(8, ledger.size(), "占用 4 行 + 释放对冲 4 行，历史不删");
        assertEquals(0, ledger.stream().mapToInt(InventoryLedger::delta).sum(), "净效果应为 0");
        assertEquals(2, ledger.stream().map(InventoryLedger::operationId).distinct().count(),
                "占用与释放是两个操作，取消后重新预订同一晚才是合法的第三个操作");
        assertEquals(0, journalMapper.sumDelta(fixture.tenantId(), fixture.hotelId(), fixture.roomId(),
                IN, EffectKind.RESERVATION), "单日净效果回到 0");
    }

    @Test
    void duplicateEffectDoesNotDoublePost() {
        Fixture fixture = fixture("j3");
        StayRange range = StayRange.of(IN, OUT);

        // 流水行的 operation_id 必须指向真实的幂等记录（V6 外键），因此先开一次操作
        UUID operationId = new TransactionTemplate(transactionManager).execute(status ->
                idempotencyService.beginInternal(fixture.tenantId(), fixture.hotelId(), "occupancy.hold",
                        cn.sec.hn.openbackupsaas.command.domain.RequestDigest.sha256Hex("duplicate-check")));
        journalService.recordOccupancyEffect(operationId, fixture.tenantId(), fixture.hotelId(),
                fixture.roomId(), range, EffectKind.RESERVATION, true, "重复提交");

        assertThrows(DataIntegrityViolationException.class, () -> journalService.recordOccupancyEffect(
                operationId, fixture.tenantId(), fixture.hotelId(), fixture.roomId(), range,
                EffectKind.RESERVATION, true, "重复提交"),
                "同一操作对同一房间同一天同一种效果只能入账一次");

        assertEquals(4, journalMapper.findLedgerOfRoom(fixture.tenantId(), fixture.hotelId(),
                fixture.roomId()).size());
    }

    @Test
    void failureAfterBusinessWriteRollsBackEverything() {
        Fixture fixture = fixture("j4");
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> template.execute(status -> {
            occupancyService.hold(fixture.tenantId(), fixture.hotelId(), fixture.reservationId(),
                    fixture.lineId(), fixture.roomId(), StayRange.of(IN, OUT), expiry(), "会被回滚");
            throw new IllegalStateException("注入失败");
        }));

        assertEquals(0, countRows("room_occupancy"), "占用必须回滚");
        assertEquals(0, countRows("inventory_ledger"), "流水必须回滚");
        assertEquals(0, countRows("audit_log"), "审计必须回滚");
        assertEquals(0, countRows("outbox_event"), "事件必须回滚");
    }

    @Test
    void conflictingHoldLeavesNoPartialFacts() {
        Fixture fixture = fixture("j5");
        occupancyService.hold(fixture.tenantId(), fixture.hotelId(), fixture.reservationId(),
                fixture.lineId(), fixture.roomId(), StayRange.of(IN, OUT), expiry(), "先到");
        int ledgerBefore = countRows("inventory_ledger");
        int eventsBefore = countRows("outbox_event");

        assertThrows(ConflictException.class, () -> occupancyService.hold(fixture.tenantId(), fixture.hotelId(),
                fixture.reservationId(), fixture.secondLineId(), fixture.roomId(),
                StayRange.of(IN.plusDays(1), OUT.plusDays(1)), expiry(), "后到"));

        assertEquals(ledgerBefore, countRows("inventory_ledger"), "冲突的那次不能留下流水");
        assertEquals(eventsBefore, countRows("outbox_event"), "冲突的那次不能留下事件");
    }

    @Test
    void auditActorComesFromTrustedContextOtherwiseSystem() {
        Fixture fixture = fixture("j6");
        UUID actorId = UUID.randomUUID();

        TenantContextHolder.bind(new TenantContext(fixture.tenantId(), actorId, "someone", Set.of(), Set.of()));
        try {
            AuditLog audit = journalService.recordAudit(fixture.tenantId(), fixture.hotelId(),
                    AuditActions.OCCUPANCY_HELD, "room_occupancy", UUID.randomUUID(), null, 1L,
                    java.util.Map.of("k", "v"));
            assertEquals(cn.sec.hn.openbackupsaas.journal.domain.ActorType.USER, audit.actorType());
            assertEquals(actorId, audit.actorId());
        } finally {
            TenantContextHolder.clear();
        }

        AuditLog systemAudit = journalService.recordAudit(fixture.tenantId(), fixture.hotelId(),
                AuditActions.OCCUPANCY_BLOCKED, "room_occupancy", UUID.randomUUID(), null, 1L,
                java.util.Map.of("k", "v"));
        assertEquals(cn.sec.hn.openbackupsaas.journal.domain.ActorType.SYSTEM, systemAudit.actorType(),
                "没有可信身份时必须如实记为 SYSTEM，不能假装是某个用户");
    }

    @Test
    void unregisteredAuditActionIsRejected() {
        Fixture fixture = fixture("j7");

        assertThrows(IllegalArgumentException.class, () -> journalService.recordAudit(fixture.tenantId(),
                fixture.hotelId(), "occupancy.madeUp", "room_occupancy", UUID.randomUUID(), null, 1L,
                java.util.Map.of()), "未登记的动作名不得写进审计，否则目录与数据会漂移");
    }

    @Test
    void blockWritesBlockKindLedger() {
        Fixture fixture = fixture("j8");
        RoomOccupancy blocked = occupancyService.block(fixture.tenantId(), fixture.hotelId(),
                fixture.roomId(), StayRange.of(IN, IN.plusDays(2)), "维修");

        List<InventoryLedger> ledger = journalMapper.findLedgerOfRoom(fixture.tenantId(), fixture.hotelId(),
                fixture.roomId());
        assertEquals(2, ledger.size());
        assertTrue(ledger.stream().allMatch(row -> row.effectKind() == EffectKind.BLOCK));

        occupancyService.changeStatus(fixture.tenantId(), fixture.hotelId(), blocked.id(),
                OccupancyStatus.UNBLOCKED);
        assertEquals(0, journalMapper.sumDelta(fixture.tenantId(), fixture.hotelId(), fixture.roomId(),
                IN, EffectKind.BLOCK));
    }

    private int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static OffsetDateTime expiry() {
        return OffsetDateTime.now().plusMinutes(30);
    }

    private Fixture fixture(String suffix) {
        Tenant tenant = organizationService.createTenant("t-" + suffix, "流水测试租户 " + suffix);
        Hotel hotel = organizationService.createHotel(tenant.id(), null, "h" + suffix, "主店", "Asia/Shanghai");
        RoomType type = roomService.createRoomType(tenant.id(), hotel.id(), "std", "标准间", 2, 0, null);
        PhysicalRoom room = roomService.createRoom(tenant.id(), hotel.id(), type.id(), "0801", 8, null);

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

        return new Fixture(tenant.id(), hotel.id(), reservation.id(), firstLine.id(), secondLine.id(), room.id());
    }

    private record Fixture(UUID tenantId, UUID hotelId, UUID reservationId, UUID lineId, UUID secondLineId,
                           UUID roomId) {
    }
}
