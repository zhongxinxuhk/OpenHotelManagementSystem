package cn.sec.hn.openbackupsaas.inventory;

import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.inventory.domain.BookableRoomType;
import cn.sec.hn.openbackupsaas.inventory.domain.InventoryDay;
import cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow;
import cn.sec.hn.openbackupsaas.inventory.domain.ReconcileReport;
import cn.sec.hn.openbackupsaas.inventory.service.InventoryService;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.service.OccupancyService;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationCommandService;
import cn.sec.hn.openbackupsaas.room.domain.PhysicalRoom;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-12 房态投影与可售计算集成测试：**必须连真实 PostgreSQL**。
 *
 * <p>覆盖 S1-12 的四条验收点：
 * <ol>
 *   <li>清空派生数据后可重建出一致结果；</li>
 *   <li>维修不重复扣减；</li>
 *   <li>每晚都有余房但没有连续可用房时，不能承诺整段住宿可售；</li>
 *   <li>投影滞后不会让查询给出与权威矛盾的结论。</li>
 * </ol>
 */
class InventoryIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);

    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private ReservationCommandService reservationCommandService;
    @Autowired
    private OccupancyService occupancyService;
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private RoomService roomService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ===================== 余量 =====================

    @Test
    void dailyAvailabilityCountsSoldRoomsPerNight() {
        Fixture f = fixture("i1", 3);
        book(f, D1, D1.plusDays(2)); // 占 1 间 2 晚

        List<InventoryDay> days = availability(f, D1, D1.plusDays(2));

        assertEquals(2, days.size(), "两晚应有两行");
        for (InventoryDay day : days) {
            assertEquals(3, day.totalRooms());
            assertEquals(1, day.soldRooms());
            assertEquals(0, day.blockedRooms());
            assertEquals(2, day.availableRooms());
        }
    }

    /**
     * 维修只扣减一次，且不与预订重复扣减。
     *
     * <p>构造：3 间房，1 间封 2 晚（维修），另 1 间被预订 2 晚。期望每晚
     * 总数 3 / 售出 1 / 维修 1 / 可售 1。若实现把"占用行数"当扣减数，
     * 或把维修与预订各算一次同一房间，数字会立刻不对。
     */
    @Test
    void blockedRoomIsDeductedOnceAndNotDoubleCountedWithReservation() {
        Fixture f = fixture("i2", 3);
        List<PhysicalRoom> rooms = rooms(f);
        StayRange stay = StayRange.of(D1, D1.plusDays(2));

        occupancyService.block(f.tenantId(), f.hotelId(), rooms.get(0).id(), stay, "维修封房");
        book(f, D1, D1.plusDays(2));

        for (InventoryDay day : availability(f, D1, D1.plusDays(2))) {
            assertEquals(3, day.totalRooms());
            assertEquals(1, day.soldRooms());
            assertEquals(1, day.blockedRooms());
            assertEquals(1, day.availableRooms());
        }
    }

    @Test
    void inactiveRoomIsNotPartOfSupply() {
        Fixture f = fixture("i3", 2);
        List<PhysicalRoom> rooms = rooms(f);
        roomService.changeRoomStatus(f.tenantId(), f.hotelId(), rooms.get(0).id(),
                cn.sec.hn.openbackupsaas.room.domain.RoomStatus.INACTIVE);

        List<InventoryDay> days = availability(f, D1, D1.plusDays(1));

        assertEquals(1, days.size());
        assertEquals(1, days.get(0).totalRooms(), "停用房不构成供给");
        assertEquals(1, days.get(0).availableRooms());
    }

    // ===================== 整段可售（核心验收） =====================

    /**
     * 每晚都有余房，但没有任何一间房能连住整段 → 整段不可售。
     *
     * <p>构造：2 间房，01 房 9/1 被封，02 房 9/2 被封。
     * 单看每晚都有 1 间空房，但 9/1—9/3 没有任何一间房能连住两晚。
     * 如果可售判断用"每日最小值"，会得出"还剩 1 间"的错误结论，
     * 客人下单必然撞上占用冲突。
     */
    @Test
    void cannotPromiseWholeStayWhenNoRoomIsContiguous() {
        Fixture f = fixture("i4", 2);
        List<PhysicalRoom> rooms = rooms(f);

        occupancyService.block(f.tenantId(), f.hotelId(), rooms.get(0).id(),
                StayRange.of(D1, D1.plusDays(1)), "维修封房");
        occupancyService.block(f.tenantId(), f.hotelId(), rooms.get(1).id(),
                StayRange.of(D1.plusDays(1), D1.plusDays(2)), "维修封房");

        List<InventoryDay> days = availability(f, D1, D1.plusDays(2));
        assertEquals(1, days.get(0).availableRooms(), "9/1 仍有余房");
        assertEquals(1, days.get(1).availableRooms(), "9/2 仍有余房");

        List<BookableRoomType> bookable = bookableRooms(f, D1, D1.plusDays(2));
        assertTrue(bookable.isEmpty() || bookable.stream().allMatch(b -> b.bookableRooms() == 0),
                "每晚都有余房但无连续可用房时，整段必须判为不可售");
    }

    @Test
    void bookableCountsRoomsFreeForTheWholeWindow() {
        Fixture f = fixture("i5", 3);
        book(f, D1, D1.plusDays(2)); // 占 1 间整段

        List<BookableRoomType> bookable = bookableRooms(f, D1, D1.plusDays(2));

        assertEquals(1, bookable.size());
        assertEquals(2, bookable.get(0).bookableRooms(), "3 间房占 1 间，整段还剩 2 间可卖");
        assertTrue(bookable.get(0).bookable());
    }

    @Test
    void adjacentStayOnCheckoutDayIsStillBookable() {
        Fixture f = fixture("i6", 1);
        book(f, D1, D1.plusDays(2));

        // 半开区间：退房日当天可以接下一单
        List<BookableRoomType> next = bookableRooms(f, D1.plusDays(2), D1.plusDays(3));
        assertEquals(1, next.size());
        assertEquals(1, next.get(0).bookableRooms(), "退房日与入住日相同即相邻，应可继续卖");
    }

    // ===================== 窗口限制 =====================

    @Test
    void windowLongerThanLimitIsRejected() {
        Fixture f = fixture("i7", 1);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new InventoryWindow(f.tenantId(), f.hotelId(), null, D1, D1.plusDays(63)));
        assertTrue(ex.getMessage().contains("62"));

        // 边界值 62 天应当允许
        assertNotNull(new InventoryWindow(f.tenantId(), f.hotelId(), null, D1, D1.plusDays(62)));
    }

    // ===================== 重建与对账 =====================

    @Test
    void rebuildProducesConsistentProjection() {
        Fixture f = fixture("i8", 3);
        book(f, D1, D1.plusDays(2));
        List<PhysicalRoom> rooms = rooms(f);
        // 封一间"没被这单选走"的房：自动选房按房号升序取第一间可用房，
        // 若封第一间会与订单占用撞区间（排斥约束会正确拒绝，但那样测的就不是房态了）
        occupancyService.block(f.tenantId(), f.hotelId(), rooms.get(2).id(),
                StayRange.of(D1, D1.plusDays(1)), "维修封房");

        InventoryWindow window = window(f, D1, D1.plusDays(2));

        // 先重建，再对账：洁净
        InventoryService.RebuildResult result = inventoryService.rebuild(window);
        assertEquals(6, result.writtenRows(), "3 间房 × 2 晚 = 6 行");

        ReconcileReport report = inventoryService.reconcile(window);
        assertTrue(report.clean(), "重建后对账应为洁净，实际差异：" + report.differences());
        assertEquals(6, report.expectedRows());
        assertEquals(6, report.storedRows());
        assertEquals(0, report.ledgerMismatches(), "占用与释放的流水净值应与占用状态一致");
    }

    @Test
    void rebuildIsIdempotent() {
        Fixture f = fixture("i9", 2);
        book(f, D1, D1.plusDays(1));
        InventoryWindow window = window(f, D1, D1.plusDays(1));

        int first = inventoryService.rebuild(window).writtenRows();
        int second = inventoryService.rebuild(window).writtenRows();

        assertEquals(first, second, "重复重建写入行数应一致");
        assertEquals(2, countProjection(f), "投影总行数不应因重复重建而膨胀");
    }

    /**
     * 清空派生数据后重建，结果与清空前一致——这就是"投影可丢弃、可重建"的直接证据。
     */
    @Test
    void projectionCanBeDiscardedAndRebuiltIdentically() {
        Fixture f = fixture("i10", 2);
        book(f, D1, D1.plusDays(2));
        InventoryWindow window = window(f, D1, D1.plusDays(2));

        inventoryService.rebuild(window);
        List<Map<String, Object>> before = projectionRows(f);

        jdbcTemplate.update("DELETE FROM room_inventory_day WHERE hotel_id = ?", f.hotelId());
        assertEquals(0, countProjection(f), "投影应已清空");

        inventoryService.rebuild(window);
        List<Map<String, Object>> after = projectionRows(f);

        assertEquals(before, after, "清空后重建的投影必须与清空前完全一致");
    }

    @Test
    void reconcileDetectsStaleAndMissingAndOrphanRows() {
        Fixture f = fixture("i11", 2);
        book(f, D1, D1.plusDays(1));
        InventoryWindow window = window(f, D1, D1.plusDays(1));

        // 完全没有投影时：全部算缺失
        ReconcileReport empty = inventoryService.reconcile(window);
        assertFalse(empty.clean());
        assertEquals(2, empty.missingRows());

        inventoryService.rebuild(window);
        assertTrue(inventoryService.reconcile(window).clean());

        // 人为把一行标记改错 → STALE
        jdbcTemplate.update("UPDATE room_inventory_day SET block_unavailable = true "
                + "WHERE hotel_id = ? AND reservation_unavailable = false", f.hotelId());
        ReconcileReport stale = inventoryService.reconcile(window);
        assertEquals(1, stale.staleRows());
        assertEquals("STALE", stale.differences().get(0).kind());

        // 人为插一行"房间已停用"的多余投影 → ORPHAN
        inventoryService.rebuild(window);
        jdbcTemplate.update("UPDATE physical_room SET status = 'INACTIVE' WHERE hotel_id = ? AND code LIKE '02%'",
                f.hotelId());
        ReconcileReport orphan = inventoryService.reconcile(window);
        assertEquals(1, orphan.orphanRows());
        assertTrue(orphan.differences().stream().anyMatch(d -> "ORPHAN".equals(d.kind())));
    }

    /**
     * 流水与占用对不上时必须报出来——这是"只比投影与重算"发现不了的一类问题。
     */
    @Test
    void reconcileDetectsLedgerMismatch() {
        Fixture f = fixture("i12", 1);
        List<PhysicalRoom> rooms = rooms(f);
        InventoryWindow window = window(f, D1, D1.plusDays(1));

        // 造一条"没有对应占用"的占用流水：净值 -1，但实际没有占用
        UUID operationId = UUID.randomUUID();
        UUID ghostRef = UUID.randomUUID();
        // 约束要求：request_hash 为 64 位十六进制；SUCCEEDED 必须有 completed_at 与 result_ref
        jdbcTemplate.update("INSERT INTO idempotency_record (operation_id, tenant_id, hotel_id, caller_namespace, "
                        + "command_name, idempotency_key, hash_version, request_hash, status, result_ref, "
                        + "created_at, completed_at) "
                        + "VALUES (?, ?, ?, 'system', 'occupancy.hold', ?, 1, ?, 'SUCCEEDED', ?, now(), now())",
                operationId, f.tenantId(), f.hotelId(), UUID.randomUUID().toString(), "a".repeat(64), ghostRef);
        jdbcTemplate.update("INSERT INTO inventory_ledger (tenant_id, hotel_id, room_id, stay_date, effect_kind, "
                        + "delta, reason_code, effective_from_date, operation_id, created_at) "
                        + "VALUES (?, ?, ?, ?, 'RESERVATION', -1, '幽灵占用', ?, ?, now())",
                f.tenantId(), f.hotelId(), rooms.get(0).id(), D1, D1, operationId);

        inventoryService.rebuild(window);
        ReconcileReport report = inventoryService.reconcile(window);

        assertFalse(report.clean());
        assertEquals(1, report.ledgerMismatches());
        assertTrue(report.differences().stream().anyMatch(d -> "LEDGER".equals(d.kind())));
    }

    // ===================== 隔离 =====================

    @Test
    void otherHotelRoomsDoNotAffectAvailability() {
        Fixture f = fixture("i13", 2);
        // 同一租户的第二家门店也建房，但不该出现在本店余量里
        RoomType otherType = roomService.createRoomType(f.tenantId(), f.secondHotelId(), "std2", "次店标准间", 2, 0, null);
        roomService.createRoom(f.tenantId(), f.secondHotelId(), otherType.id(), "0901", 9, null);

        List<InventoryDay> days = availability(f, D1, D1.plusDays(1));

        assertEquals(1, days.size());
        assertEquals(2, days.get(0).totalRooms(), "只统计本店房间");
    }

    // ===================== 辅助 =====================

    private List<InventoryDay> availability(Fixture f, LocalDate from, LocalDate to) {
        return inventoryService.dailyAvailability(new InventoryWindow(f.tenantId(), f.hotelId(), f.typeA(), from, to));
    }

    private List<BookableRoomType> bookableRooms(Fixture f, LocalDate from, LocalDate to) {
        return inventoryService.bookableRooms(new InventoryWindow(f.tenantId(), f.hotelId(), f.typeA(), from, to));
    }

    private InventoryWindow window(Fixture f, LocalDate from, LocalDate to) {
        return new InventoryWindow(f.tenantId(), f.hotelId(), f.typeA(), from, to);
    }

    private void book(Fixture f, LocalDate from, LocalDate to) {
        reservationCommandService.createReservation(f.tenantId(),
                new ReservationCommandService.CreateCommand(f.hotelId(), "test-client",
                        "create-" + UUID.randomUUID(), ReservationSourceType.FRONTDESK,
                        List.of(new ReservationCommandService.CreateCommand.LineCommand(
                                f.typeA(), StayRange.of(from, to)))));
    }

    private List<PhysicalRoom> rooms(Fixture f) {
        return roomService.listRooms(f.tenantId(), f.hotelId(), f.typeA(), null, null);
    }

    private int countProjection(Fixture f) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM room_inventory_day WHERE hotel_id = ?", Integer.class, f.hotelId());
        return value == null ? 0 : value;
    }

    private List<Map<String, Object>> projectionRows(Fixture f) {
        return jdbcTemplate.queryForList("SELECT room_id, stay_date, reservation_unavailable, block_unavailable "
                + "FROM room_inventory_day WHERE hotel_id = ? ORDER BY room_id, stay_date", f.hotelId());
    }

    private Fixture fixture(String suffix, int rooms) {
        Tenant tenant = organizationService.createTenant("t-" + suffix, "房态测试租户 " + suffix);
        Hotel hotel = organizationService.createHotel(tenant.id(), null, "h" + suffix, "主店 " + suffix,
                "Asia/Shanghai");
        Hotel second = organizationService.createHotel(tenant.id(), null, "s" + suffix, "次店 " + suffix,
                "Asia/Shanghai");
        RoomType typeA = roomService.createRoomType(tenant.id(), hotel.id(), "std", "标准间", 2, 0, null);
        for (int i = 0; i < rooms; i++) {
            roomService.createRoom(tenant.id(), hotel.id(), typeA.id(), "0" + (i + 1) + suffix, i + 1, null);
        }
        return new Fixture(tenant.id(), hotel.id(), second.id(), typeA.id(), suffix);
    }

    private record Fixture(UUID tenantId, UUID hotelId, UUID secondHotelId, UUID typeA, String suffix) {
    }
}
