package cn.sec.hn.openbackupsaas.reconcile;

import cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow;
import cn.sec.hn.openbackupsaas.inventory.service.InventoryService;
import cn.sec.hn.openbackupsaas.reconcile.domain.FindingKind;
import cn.sec.hn.openbackupsaas.reconcile.domain.FindingSeverity;
import cn.sec.hn.openbackupsaas.reconcile.domain.FindingView;
import cn.sec.hn.openbackupsaas.reconcile.domain.ReconcileRunReport;
import cn.sec.hn.openbackupsaas.reconcile.service.ReconcileService;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationCommandService;
import cn.sec.hn.openbackupsaas.room.domain.PhysicalRoom;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2-05 内部对账与派生修复的集成测试：**必须连真实 PostgreSQL**。
 *
 * <p>逐条对验收：
 * <ol>
 *   <li>故意构造缺失投影、重复投递、流水不符等异常能被发现；</li>
 *   <li>重复跑同一批次不重复建有效工单；</li>
 *   <li>不能用派生值覆盖交易事实，也不能自动"补造"订单。</li>
 * </ol>
 *
 * <p>最后一条是本轮最要紧的：它没法用"功能对不对"来测，只能<b>故意制造矛盾</b>，
 * 然后断言对账除了写下"这里有问题"之外什么都没做。
 */
class ReconcileIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDate D1 = LocalDate.of(2026, 7, 1);

    @Autowired
    private ReconcileService reconcileService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private ReservationCommandService reservationCommandService;
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private RoomService roomService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================================================================
    // 健康数据：不应该报问题
    // ==================================================================

    @Test
    void healthyHotelProducesNoFindings() {
        Fixture f = fixture("r1", 2);
        book(f, D1, D1.plusDays(2));
        inventoryService.rebuild(window(f, D1, D1.plusDays(3)));

        ReconcileRunReport report = run(f, D1, D1.plusDays(3), false);

        assertEquals(0, report.newFindings(), "重建过投影的健康数据不该有任何差异");
        assertEquals(0, report.openFindings());
        assertFalse(report.needsAttention());
    }

    // ==================================================================
    // 差异发现
    // ==================================================================

    @Test
    void missingProjectionIsDetected() {
        Fixture f = fixture("r2", 2);
        book(f, D1, D1.plusDays(2));
        inventoryService.rebuild(window(f, D1, D1.plusDays(3)));
        // 挖掉一行：模拟投影滞后
        int deleted = jdbcTemplate.update("DELETE FROM room_inventory_day "
                + "WHERE tenant_id = ? AND hotel_id = ? AND stay_date = ?", f.tenantId(), f.hotelId(), D1);
        assertTrue(deleted > 0, "前置条件：投影本该有行");

        ReconcileRunReport report = run(f, D1, D1.plusDays(3), false);

        assertTrue(report.newFindings() > 0, "删掉的投影行必须被发现");
        List<FindingView> findings = reconcileService.openFindings(f.tenantId(), f.hotelId(), null, 100);
        assertTrue(findings.stream().anyMatch(x -> x.kind() == FindingKind.MISSING_PROJECTION),
                "应记一条 MISSING_PROJECTION，实际 " + findings.stream().map(FindingView::kind).toList());
        FindingView missing = findings.stream()
                .filter(x -> x.kind() == FindingKind.MISSING_PROJECTION).findFirst().orElseThrow();
        assertEquals(FindingSeverity.DERIVED, missing.severity());
        assertTrue(missing.repairable(), "派生类差异应标记为可自动修复");
    }

    @Test
    void ledgerMismatchIsDetectedAsFact() {
        Fixture f = fixture("r3", 2);
        book(f, D1, D1.plusDays(2));
        inventoryService.rebuild(window(f, D1, D1.plusDays(3)));
        PhysicalRoom room = rooms(f).get(0);
        // 造一条"没有对应占用"的释放流水：净值 +1，但该房该晚本来没被占。
        // 流水对 operation_id 有外键（指向幂等记录），所以先补一条幂等记录——
        // 这恰好说明"幽灵流水"在真实系统里也只能从命令入口产生，不是随便插得进来的。
        UUID operationId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO idempotency_record (operation_id, tenant_id, hotel_id, "
                        + "caller_namespace, command_name, idempotency_key, hash_version, request_hash, "
                        + "status, completed_at, result_ref, created_at) "
                        + "VALUES (?, ?, ?, 'system', 'occupancy.release', ?, 1, ?, 'SUCCEEDED', now(), ?, now())",
                operationId, f.tenantId(), f.hotelId(), UUID.randomUUID().toString(), "a".repeat(64),
                UUID.randomUUID());
        jdbcTemplate.update("INSERT INTO inventory_ledger (tenant_id, hotel_id, room_id, stay_date, "
                        + "delta, effect_kind, operation_id, reason_code, effective_from_date) "
                        + "VALUES (?, ?, ?, ?, 1, 'RESERVATION', ?, 'GHOST', ?)",
                f.tenantId(), f.hotelId(), room.id(), D1.plusDays(1), operationId, D1.plusDays(1));

        run(f, D1, D1.plusDays(3), true);

        List<FindingView> findings = reconcileService.openFindings(f.tenantId(), f.hotelId(),
                FindingSeverity.FACT, 100);
        assertTrue(findings.stream().anyMatch(x -> x.kind() == FindingKind.LEDGER_MISMATCH),
                "幽灵流水必须被发现，实际 " + findings.stream().map(FindingView::kind).toList());
    }

    @Test
    void reservationWithoutEffectiveOccupancyIsDetected() {
        Fixture f = fixture("r4", 2);
        book(f, D1, D1.plusDays(2));
        inventoryService.rebuild(window(f, D1, D1.plusDays(3)));
        // 把占用挪成终态：订单仍是 PENDING，但房间事实上没被占住（超卖前兆）
        int updated = jdbcTemplate.update("UPDATE room_occupancy SET status = 'RELEASED' "
                + "WHERE tenant_id = ? AND hotel_id = ? AND kind = 'RESERVATION'", f.tenantId(), f.hotelId());
        assertTrue(updated > 0);

        run(f, D1, D1.plusDays(3), true);

        List<FindingView> findings = reconcileService.openFindings(f.tenantId(), f.hotelId(),
                FindingSeverity.FACT, 100);
        assertTrue(findings.stream().anyMatch(x -> x.kind() == FindingKind.MISSING_OCCUPANCY),
                "有效订单没有有效占用必须被发现，实际 " + findings.stream().map(FindingView::kind).toList());
    }

    @Test
    void outboxBacklogIsDetectedAsBacklogNotAsDataProblem() {
        Fixture f = fixture("r5", 1);
        book(f, D1, D1.plusDays(1));
        inventoryService.rebuild(window(f, D1, D1.plusDays(2)));
        // 一条很老的未发布事件：数据没错，是投递链路跟不上了
        jdbcTemplate.update("INSERT INTO outbox_event (event_id, tenant_id, hotel_id, aggregate_type, "
                        + "aggregate_id, aggregate_version, event_ordinal, event_type, payload, "
                        + "occurred_at, next_attempt_at) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'Reservation', gen_random_uuid(), 1, 1, "
                        + "'ReservationCreated', '{}'::jsonb, now() - interval '2 hours', now())",
                f.tenantId(), f.hotelId());

        run(f, D1, D1.plusDays(2), true);

        List<FindingView> backlog = reconcileService.openFindings(f.tenantId(), f.hotelId(),
                FindingSeverity.BACKLOG, 100);
        assertTrue(backlog.stream().anyMatch(x -> x.kind() == FindingKind.OUTBOX_BACKLOG),
                "超龄未发布事件必须被发现，实际 " + backlog.stream().map(FindingView::kind).toList());
        assertEquals(1, backlog.size(), "积压按门店汇总成一张工单，不是每条事件一张");
    }

    // ==================================================================
    // 工单去重（重复跑不新建）
    // ==================================================================

    @Test
    void repeatedRunReusesTheSameTicket() {
        Fixture f = fixture("r6", 2);
        book(f, D1, D1.plusDays(2));
        inventoryService.rebuild(window(f, D1, D1.plusDays(3)));
        jdbcTemplate.update("DELETE FROM room_inventory_day WHERE tenant_id = ? AND hotel_id = ? "
                + "AND stay_date = ?", f.tenantId(), f.hotelId(), D1);

        ReconcileRunReport first = run(f, D1, D1.plusDays(3), false);
        int afterFirst = countOpen(f);
        ReconcileRunReport second = run(f, D1, D1.plusDays(3), false);
        int afterSecond = countOpen(f);

        assertTrue(first.newFindings() > 0);
        assertEquals(0, second.newFindings(), "第二轮不应再有新发现");
        assertEquals(first.newFindings(), second.repeatedFindings(), "第二轮应全部计为重复出现");
        assertEquals(afterFirst, afterSecond, "重复扫描不得新建工单（否则看板会被刷屏）");

        Integer maxOccurrences = jdbcTemplate.queryForObject(
                "SELECT max(occurrences) FROM reconcile_finding WHERE hotel_id = ? AND status = 'OPEN'",
                Integer.class, f.hotelId());
        assertEquals(2, maxOccurrences, "重复出现应累加 occurrences，而不是新建一行");
    }

    // ==================================================================
    // 自动修复的边界：只动派生
    // ==================================================================

    @Test
    void autoRepairFixesDerivedFindings() {
        Fixture f = fixture("r7", 2);
        book(f, D1, D1.plusDays(2));
        inventoryService.rebuild(window(f, D1, D1.plusDays(3)));
        jdbcTemplate.update("DELETE FROM room_inventory_day WHERE tenant_id = ? AND hotel_id = ? "
                + "AND stay_date = ?", f.tenantId(), f.hotelId(), D1);
        assertTrue(countProjection(f) < 6, "前置条件：投影确实少了几行");

        ReconcileRunReport report = run(f, D1, D1.plusDays(3), true);

        assertTrue(report.repairedFindings() > 0, "派生差异应被自动修复");
        assertEquals(0, reconcileService.openFindings(f.tenantId(), f.hotelId(),
                FindingSeverity.DERIVED, 100).size(), "修好后不应再有未结的派生差异");
        assertEquals(6, countProjection(f), "重建后投影应补齐为 2 间 × 3 晚");
    }

    @Test
    void autoRepairLeavesFactFindingsOpenAndUntouched() {
        Fixture f = fixture("r8", 2);
        book(f, D1, D1.plusDays(2));
        inventoryService.rebuild(window(f, D1, D1.plusDays(3)));
        // 制造事实矛盾：订单被直接改成 CANCELLED，占用仍是 HELD
        jdbcTemplate.update("UPDATE reservation SET status = 'CANCELLED' WHERE tenant_id = ? AND hotel_id = ?",
                f.tenantId(), f.hotelId());

        ReconcileRunReport report = run(f, D1, D1.plusDays(3), true);

        assertTrue(report.openFact() > 0, "矛盾必须被记为事实类差异");
        List<FindingView> facts = reconcileService.openFindings(f.tenantId(), f.hotelId(),
                FindingSeverity.FACT, 100);
        assertFalse(facts.isEmpty());
        for (FindingView fact : facts) {
            assertFalse(fact.repairable(), "事实类差异不允许被标为可修复：" + fact.kind());
        }
        // 关键：自动修复不得"顺手把占用量改成与订单一致"
        Integer held = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM room_occupancy WHERE tenant_id = ? AND status = 'HELD'",
                Integer.class, f.tenantId());
        assertTrue(held != null && held > 0,
                "对账不能改写占用状态——那是交易事实，改一致就等于销毁『哪边错了』的证据");
    }

    @Test
    void reconcileNeverCreatesOrDeletesBusinessRows() {
        Fixture f = fixture("r9", 2);
        book(f, D1, D1.plusDays(2));
        inventoryService.rebuild(window(f, D1, D1.plusDays(3)));
        // 同时制造派生问题与事实问题
        jdbcTemplate.update("DELETE FROM room_inventory_day WHERE tenant_id = ? AND hotel_id = ? "
                + "AND stay_date = ?", f.tenantId(), f.hotelId(), D1);
        jdbcTemplate.update("UPDATE reservation SET status = 'CANCELLED' WHERE tenant_id = ? AND hotel_id = ?",
                f.tenantId(), f.hotelId());

        int reservationsBefore = count(f, "reservation");
        int linesBefore = count(f, "reservation_line");
        int occupancyBefore = count(f, "room_occupancy");
        int ledgerBefore = count(f, "inventory_ledger");

        run(f, D1, D1.plusDays(3), true);

        assertEquals(reservationsBefore, count(f, "reservation"), "对账不得新增或删除订单");
        assertEquals(linesBefore, count(f, "reservation_line"), "对账不得新增或删除订单行");
        assertEquals(occupancyBefore, count(f, "room_occupancy"), "对账不得新增或删除占用");
        assertEquals(ledgerBefore, count(f, "inventory_ledger"), "对账不得补写流水");
    }

    // ==================================================================
    // 退场与水位
    // ==================================================================

    @Test
    void problemDisappearingSupersedesTheTicket() {
        Fixture f = fixture("r10", 2);
        book(f, D1, D1.plusDays(2));
        inventoryService.rebuild(window(f, D1, D1.plusDays(3)));
        jdbcTemplate.update("DELETE FROM room_inventory_day WHERE tenant_id = ? AND hotel_id = ? "
                + "AND stay_date = ?", f.tenantId(), f.hotelId(), D1);
        run(f, D1, D1.plusDays(3), false);
        assertTrue(countOpen(f) > 0);

        // 人（或运维）把投影补回来了
        inventoryService.rebuild(window(f, D1, D1.plusDays(3)));
        ReconcileRunReport second = run(f, D1, D1.plusDays(3), false);

        assertTrue(second.supersededFindings() > 0, "问题消失后工单应自动退场");
        assertEquals(0, countOpen(f), "不该继续挂着已经不存在的问题");
        Integer superseded = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconcile_finding WHERE hotel_id = ? AND status = 'SUPERSEDED'",
                Integer.class, f.hotelId());
        assertTrue(superseded != null && superseded > 0);
    }

    @Test
    void windowOutsideScanRangeIsNotSuperseded() {
        Fixture f = fixture("r11", 1);
        book(f, D1, D1.plusDays(1));
        inventoryService.rebuild(window(f, D1, D1.plusDays(2)));
        jdbcTemplate.update("DELETE FROM room_inventory_day WHERE tenant_id = ? AND hotel_id = ? "
                + "AND stay_date = ?", f.tenantId(), f.hotelId(), D1);
        run(f, D1, D1.plusDays(2), false);
        int openAfterFirst = countOpen(f);
        assertTrue(openAfterFirst > 0);

        // 只扫后面一段（不覆盖 D1）：先把这段的投影建好，让本轮除了"看见老问题"之外不产生新差异，
        // 否则新窗口自身的缺失投影会混进来，测不清"老问题有没有被误关"
        inventoryService.rebuild(window(f, D1.plusDays(5), D1.plusDays(7)));
        run(f, D1.plusDays(5), D1.plusDays(7), false);

        assertEquals(openAfterFirst, countOpen(f),
                "扫描范围之外的老问题不得退场——否则只扫最近几天的对账能把历史问题全部『自动解决』");
    }

    @Test
    void watermarkAdvancesAndNeverGoesBackwards() {
        Fixture f = fixture("r12", 1);
        book(f, D1, D1.plusDays(1));
        inventoryService.rebuild(window(f, D1, D1.plusDays(2)));

        run(f, D1, D1.plusDays(3), false);
        assertEquals(D1.plusDays(2), reconcileService.watermark(f.tenantId(), f.hotelId()),
                "水位应记成『最后一个已扫过的住宿日』");

        // 重扫更早的区间：水位不能被拉回去，否则定时任务会重复扫旧数据
        run(f, D1.minusDays(10), D1.minusDays(5), false);
        assertEquals(D1.plusDays(2), reconcileService.watermark(f.tenantId(), f.hotelId()),
                "重扫历史区间不得把水位拉回去");
    }

    // ==================================================================
    // 修复接口的语义
    // ==================================================================

    @Test
    void factFindingCannotBeRepairedThroughApi() {
        Fixture f = fixture("r13", 1);
        book(f, D1, D1.plusDays(1));
        inventoryService.rebuild(window(f, D1, D1.plusDays(2)));
        jdbcTemplate.update("UPDATE reservation SET status = 'CANCELLED' WHERE tenant_id = ? AND hotel_id = ?",
                f.tenantId(), f.hotelId());
        run(f, D1, D1.plusDays(2), false);

        FindingView fact = reconcileService.openFindings(f.tenantId(), f.hotelId(),
                FindingSeverity.FACT, 100).get(0);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> reconcileService.repairFinding(f.tenantId(), f.hotelId(), fact.findingId()));
        assertTrue(ex.getMessage().contains("不允许自动修复"), "实际：" + ex.getMessage());
        assertTrue(reconcileService.findFinding(f.tenantId(), f.hotelId(), fact.findingId()).open(),
                "拒绝修复后工单必须仍是 OPEN——偷偷关掉等于把矛盾藏起来");
    }

    @Test
    void ignoreRequiresReasonAndClosesTicket() {
        Fixture f = fixture("r14", 1);
        book(f, D1, D1.plusDays(1));
        inventoryService.rebuild(window(f, D1, D1.plusDays(2)));
        jdbcTemplate.update("DELETE FROM room_inventory_day WHERE tenant_id = ? AND hotel_id = ? "
                + "AND stay_date = ?", f.tenantId(), f.hotelId(), D1);
        run(f, D1, D1.plusDays(2), false);
        FindingView finding = reconcileService.openFindings(f.tenantId(), f.hotelId(), null, 100).get(0);

        assertThrows(IllegalArgumentException.class,
                () -> reconcileService.ignoreFinding(f.tenantId(), f.hotelId(), finding.findingId(),
                        UUID.randomUUID(), "  "));

        reconcileService.ignoreFinding(f.tenantId(), f.hotelId(), finding.findingId(),
                UUID.randomUUID(), "投影将在今日维护窗口重建");
        FindingView settled = reconcileService.findFinding(f.tenantId(), f.hotelId(), finding.findingId());
        assertEquals("IGNORED", settled.status());
        assertNotNull(settled.settleReason(), "忽略必须留原因（数据库也会拒绝空原因）");
    }

    @Test
    void unknownOrCrossHotelTicketIsNotFound() {
        Fixture f = fixture("r15", 1);
        Fixture other = fixture("r15b", 1);
        book(f, D1, D1.plusDays(1));
        inventoryService.rebuild(window(f, D1, D1.plusDays(2)));
        jdbcTemplate.update("DELETE FROM room_inventory_day WHERE tenant_id = ? AND hotel_id = ? "
                + "AND stay_date = ?", f.tenantId(), f.hotelId(), D1);
        run(f, D1, D1.plusDays(2), false);
        FindingView finding = reconcileService.openFindings(f.tenantId(), f.hotelId(), null, 100).get(0);

        // 另一家门店（同租户不同店）看不到这条工单
        assertThrows(NotFoundException.class,
                () -> reconcileService.repairFinding(other.tenantId(), other.hotelId(), finding.findingId()));
        assertNull(reconcileService.findFinding(other.tenantId(), other.hotelId(), finding.findingId()));
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private ReconcileRunReport run(Fixture f, LocalDate from, LocalDate to, boolean autoRepair) {
        return reconcileService.run(f.tenantId(), f.hotelId(), window(f, from, to),
                "MANUAL", null, autoRepair);
    }

    private InventoryWindow window(Fixture f, LocalDate from, LocalDate to) {
        return new InventoryWindow(f.tenantId(), f.hotelId(), null, from, to);
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
                "SELECT count(*) FROM room_inventory_day WHERE tenant_id = ? AND hotel_id = ?",
                Integer.class, f.tenantId(), f.hotelId());
        return value == null ? 0 : value;
    }

    private int countOpen(Fixture f) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconcile_finding WHERE tenant_id = ? AND hotel_id = ? AND status = 'OPEN'",
                Integer.class, f.tenantId(), f.hotelId());
        return value == null ? 0 : value;
    }

    private int count(Fixture f, String table) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE tenant_id = ? AND hotel_id = ?",
                Integer.class, f.tenantId(), f.hotelId());
        return value == null ? 0 : value;
    }

    private Fixture fixture(String suffix, int roomCount) {
        Tenant tenant = organizationService.createTenant("t-" + suffix, "对账测试租户 " + suffix);
        Hotel hotel = organizationService.createHotel(tenant.id(), null, "h" + suffix, "主店 " + suffix,
                "Asia/Shanghai");
        Hotel second = organizationService.createHotel(tenant.id(), null, "s" + suffix, "次店 " + suffix,
                "Asia/Shanghai");
        RoomType typeA = roomService.createRoomType(tenant.id(), hotel.id(), "std", "标准间", 2, 0, null);
        for (int i = 0; i < roomCount; i++) {
            roomService.createRoom(tenant.id(), hotel.id(), typeA.id(), "0" + (i + 1) + suffix, i + 1, null);
        }
        return new Fixture(tenant.id(), hotel.id(), second.id(), typeA.id(), suffix);
    }

    private record Fixture(UUID tenantId, UUID hotelId, UUID secondHotelId, UUID typeA, String suffix) {
    }
}
