package cn.sec.hn.openbackupsaas.reservation;

import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationQuery;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSummary;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationCommandService;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationQueryService;
import cn.sec.hn.openbackupsaas.reservation.web.ReservationDetailView;
import cn.sec.hn.openbackupsaas.reservation.web.ReservationView;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import cn.sec.hn.openbackupsaas.shared.web.PageView;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-11 订单查询集成测试：**必须连真实 PostgreSQL**。
 *
 * <p>要证明的是 S1-11 的验收点，而不是"能查出数据"：
 * 过滤条件真的下推并能命中；分页在"同一毫秒创建多单"时仍不重不漏；
 * 别家门店/别家租户的订单既查不到也看不出存在性；详情给出正确的房号与可执行动作。
 */
class ReservationQueryIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDate IN = LocalDate.of(2026, 6, 1);
    private static final LocalDate OUT = LocalDate.of(2026, 6, 4); // 3 晚

    @Autowired
    private ReservationQueryService queryService;
    @Autowired
    private ReservationCommandService commandService;
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private RoomService roomService;

    @Test
    void listAggregatesLineCountAndOverallSpan() {
        Fixture f = fixture("q1", 2);
        ReservationView created = create(f, "c1", StayRange.of(IN, OUT), StayRange.of(IN.plusDays(1), OUT.plusDays(1)));

        PageView<ReservationSummary> page = queryService.search(defaultQuery(f));

        assertEquals(1, page.total());
        assertEquals(1, page.totalPages());
        ReservationSummary row = page.items().get(0);
        assertEquals(created.id(), row.id());
        assertEquals(2, row.lineCount());
        // 整单跨度取两行的并集：最早入住 → 最晚离店
        assertEquals(IN, row.checkIn());
        assertEquals(OUT.plusDays(1), row.checkOut());
        assertEquals(ReservationStatus.PENDING, row.status());
    }

    @Test
    void statusFilterNarrowsResult() {
        Fixture f = fixture("q2", 2);
        ReservationView keep = create(f, "c1", StayRange.of(IN, OUT));
        ReservationView cancel = create(f, "c2", StayRange.of(IN, OUT));
        commandService.cancelReservation(f.tenantId(), cancel.id(),
                new ReservationCommandService.LifecycleCommand(f.hotelId(), "test-client", "cancel-1", null));

        PageView<ReservationSummary> pending = queryService.search(new ReservationQuery(f.tenantId(), f.hotelId(),
                List.of(ReservationStatus.PENDING), null, null, null, null, null, 1, 20));
        assertEquals(1, pending.total());
        assertEquals(keep.id(), pending.items().get(0).id());

        PageView<ReservationSummary> both = queryService.search(new ReservationQuery(f.tenantId(), f.hotelId(),
                List.of(ReservationStatus.PENDING, ReservationStatus.CANCELLED), null, null, null, null, null, 1, 20));
        assertEquals(2, both.total());
    }

    /**
     * 入住区间按**交集**筛选，不是"某一天落在区间内"。
     *
     * <p>查 6/2—6/5 必须命中 6/1—6/4 的单（重叠区间），也必须命中 6/3—6/6 的单（后者覆盖查询区间的一部分）。
     */
    @Test
    void stayRangeFilterUsesIntersectionNotContainment() {
        Fixture f = fixture("q3", 3);
        ReservationView overlapping = create(f, "c1", StayRange.of(IN, OUT));                    // 6/1—6/4
        ReservationView later = create(f, "c2", StayRange.of(IN.plusDays(2), IN.plusDays(5)));   // 6/3—6/6
        ReservationView disjoint = create(f, "c3", StayRange.of(IN.plusDays(10), IN.plusDays(12)));

        PageView<ReservationSummary> page = queryService.search(new ReservationQuery(f.tenantId(), f.hotelId(),
                null, null, null, null, IN.plusDays(1), IN.plusDays(4), 1, 20));

        Set<UUID> ids = page.items().stream().map(ReservationSummary::id).collect(Collectors.toSet());
        assertEquals(2, page.total());
        assertTrue(ids.contains(overlapping.id()), "重叠区间应命中");
        assertTrue(ids.contains(later.id()), "覆盖查询区间一部分的订单也应命中");
        assertFalse(ids.contains(disjoint.id()), "不相交的订单不应命中");
    }

    @Test
    void reservationNoPrefixFilterWorks() {
        Fixture f = fixture("q4", 2);
        ReservationView first = create(f, "c1", StayRange.of(IN, OUT));
        create(f, "c2", StayRange.of(IN, OUT));

        // 用完整单号做前缀：同一天创建的单号前 8 位（RSV+日期）相同，
        // 取短前缀会同时命中两单，断言就没有区分度了。
        PageView<ReservationSummary> page = queryService.search(new ReservationQuery(f.tenantId(), f.hotelId(),
                null, null, first.reservationNo(), null, null, null, 1, 20));
        assertEquals(1, page.total());
        assertEquals(first.id(), page.items().get(0).id());

        // 通配符必须被转义：输入一个 % 不能被当成"匹配所有"
        PageView<ReservationSummary> wildcard = queryService.search(new ReservationQuery(f.tenantId(), f.hotelId(),
                null, null, "%", null, null, null, 1, 20));
        assertEquals(0, wildcard.total(), "LIKE 通配符必须被转义，否则前缀条件会退化成全表匹配");
    }

    @Test
    void roomCodeFilterFindsReservation() {
        Fixture f = fixture("q5", 1);
        ReservationView created = create(f, "c1", StayRange.of(IN, OUT));
        ReservationDetailView detail = queryService.detail(f.tenantId(), f.hotelId(), created.id());
        String roomCode = detail.lines().get(0).assignedRoomCode();
        assertNotNull(roomCode, "创建后应已分配实体房");

        PageView<ReservationSummary> page = queryService.search(new ReservationQuery(f.tenantId(), f.hotelId(),
                null, null, null, roomCode, null, null, 1, 20));

        assertEquals(1, page.total());
        assertEquals(created.id(), page.items().get(0).id());
    }

    /**
     * 分页稳定性：5 单在同一时刻创建（created_at 可能完全相同），
     * 靠 id 兜底排序也不能出现重复或遗漏。这正是"只按 created_at 排序"会翻车的地方。
     */
    @Test
    void paginationIsStableWhenCreatedAtIsIdentical() {
        Fixture f = fixture("q6", 5);
        for (int i = 1; i <= 5; i++) {
            create(f, "c" + i, StayRange.of(IN, OUT));
        }

        PageView<ReservationSummary> p1 = queryService.search(page(f, 1, 2));
        PageView<ReservationSummary> p2 = queryService.search(page(f, 2, 2));
        PageView<ReservationSummary> p3 = queryService.search(page(f, 3, 2));

        assertEquals(5, p1.total());
        assertEquals(3, p1.totalPages());

        List<UUID> collected = List.of(
                p1.items().get(0).id(), p1.items().get(1).id(),
                p2.items().get(0).id(), p2.items().get(1).id(),
                p3.items().get(0).id());
        assertEquals(5, collected.stream().distinct().count(), "三页合并必须不重不漏");
    }

    @Test
    void otherHotelInSameTenantIsInvisible() {
        Fixture f = fixture("q7", 1);
        create(f, "c1", StayRange.of(IN, OUT));

        // 同一租户、另一家门店：列表为空
        PageView<ReservationSummary> otherHotel = queryService.search(new ReservationQuery(f.tenantId(),
                f.secondHotelId(), null, null, null, null, null, null, 1, 20));
        assertEquals(0, otherHotel.total());
    }

    @Test
    void otherTenantReservationIsNotFoundNotForbidden() {
        Fixture mine = fixture("q8", 1);
        Fixture theirs = fixture("q9", 1);
        ReservationView foreign = create(theirs, "c1", StayRange.of(IN, OUT));

        // 用"我的租户 + 我的门店"去查别人的订单：结果必须与"根本不存在的 id"一样
        assertThrows(NotFoundException.class,
                () -> queryService.detail(mine.tenantId(), mine.hotelId(), foreign.id()));
        assertThrows(NotFoundException.class,
                () -> queryService.detail(mine.tenantId(), mine.hotelId(), UUID.randomUUID()));
    }

    @Test
    void detailExposesAssignmentAndAllowedActions() {
        Fixture f = fixture("q10", 1);
        ReservationView created = create(f, "c1", StayRange.of(IN, OUT));

        ReservationDetailView detail = queryService.detail(f.tenantId(), f.hotelId(), created.id());

        assertEquals(ReservationStatus.PENDING, detail.status());
        assertEquals(0L, detail.version());
        assertEquals(StayRange.of(IN, OUT), detail.stayRange());
        assertEquals(1, detail.lines().size());
        assertEquals("HELD", detail.lines().get(0).occupancyStatus().name());
        assertNotNull(detail.lines().get(0).assignedRoomCode());
        // PENDING 的整单：确认与取消都可执行，No-show 还不允许
        assertEquals(List.of("CONFIRM", "CANCEL"), detail.allowedActions());
    }

    @Test
    void cancelledReservationOffersNoActions() {
        Fixture f = fixture("q11", 1);
        ReservationView created = create(f, "c1", StayRange.of(IN, OUT));
        commandService.cancelReservation(f.tenantId(), created.id(),
                new ReservationCommandService.LifecycleCommand(f.hotelId(), "test-client", "cancel-1", null));

        ReservationDetailView detail = queryService.detail(f.tenantId(), f.hotelId(), created.id());

        assertEquals(ReservationStatus.CANCELLED, detail.status());
        assertTrue(detail.allowedActions().isEmpty(), "已取消订单不应再给出任何动作");
        assertEquals("RELEASED", detail.lines().get(0).occupancyStatus().name());
    }

    @Test
    void outOfRangePageReturnsEmptyButKeepsTotal() {
        Fixture f = fixture("q12", 2);
        create(f, "c1", StayRange.of(IN, OUT));
        create(f, "c2", StayRange.of(IN, OUT));

        PageView<ReservationSummary> page = queryService.search(page(f, 9, 20));

        assertTrue(page.items().isEmpty());
        assertEquals(2, page.total());
        assertEquals(1, page.totalPages());
    }

    // ===================== 辅助 =====================

    private ReservationQuery defaultQuery(Fixture f) {
        return new ReservationQuery(f.tenantId(), f.hotelId(), null, null, null, null, null, null, 1, 20);
    }

    private ReservationQuery page(Fixture f, int page, int size) {
        return new ReservationQuery(f.tenantId(), f.hotelId(), null, null, null, null, null, null, page, size);
    }

    private ReservationView create(Fixture f, String key, StayRange... ranges) {
        List<ReservationCommandService.CreateCommand.LineCommand> lines = java.util.Arrays.stream(ranges)
                .map(range -> new ReservationCommandService.CreateCommand.LineCommand(f.typeA(), range))
                .toList();
        return commandService.createReservation(f.tenantId(),
                new ReservationCommandService.CreateCommand(f.hotelId(), "test-client",
                        key + "-" + f.suffix(), ReservationSourceType.FRONTDESK, lines));
    }

    private Fixture fixture(String suffix, int rooms) {
        Tenant tenant = organizationService.createTenant("t-" + suffix, "查询测试租户 " + suffix);
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
