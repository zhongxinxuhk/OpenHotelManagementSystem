package cn.sec.hn.openbackupsaas.room;

import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.room.domain.HousekeepingStatus;
import cn.sec.hn.openbackupsaas.room.domain.PhysicalRoom;
import cn.sec.hn.openbackupsaas.room.domain.RoomStatus;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.mapper.RoomMapper;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.OrgStatus;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-04 房型与实体房集成测试：**必须连真实 PostgreSQL**。
 *
 * <p>运行方式：
 * <pre>
 *   ./scripts/dev-db.sh start
 *   PMS_IT_DB=true ./mvnw -B test -Dtest=RoomIntegrationTest
 * </pre>
 * 未设置 {@code PMS_IT_DB=true} 时整类跳过。
 *
 * <p>覆盖 S1-04 验收项：同店房号唯一、跨店房型归属被拒绝（服务层与数据库层各验一次）、
 * 启停规则、清洁状态与销售事实分离。**不覆盖**：有有效预订时的停用处置（依赖 S1-05 占用表）。
 */
class RoomIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomMapper roomMapper;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createRoomTypeNormalizesCodeAndKeepsCapacity() {
        Fixture fixture = fixture("a1");

        RoomType roomType = roomService.createRoomType(fixture.tenantId(), fixture.hotelId(),
                "DLX-King", "  豪华大床房  ", 3, 1, "KING");

        assertEquals("dlx-king", roomType.code(), "房型编码统一小写");
        assertEquals("豪华大床房", roomType.name(), "名称去掉首尾空白");
        assertEquals(3, roomType.capacityAdults());
        assertEquals(1, roomType.capacityChildren());
        assertEquals("KING", roomType.bedType());
        assertEquals(OrgStatus.ACTIVE, roomType.status(), "新建房型默认可售");
    }

    @Test
    void duplicateRoomTypeCodeRejectedByDatabase() {
        Fixture fixture = fixture("a2");
        roomService.createRoomType(fixture.tenantId(), fixture.hotelId(), "std", "标准间", 2, 0, null);

        assertThrows(DataIntegrityViolationException.class, () -> roomService.createRoomType(
                fixture.tenantId(), fixture.hotelId(), "STD", "重复标准间", 2, 0, null),
                "uq_room_type_code 必须挡住同店重复编码（大小写归一后相同）");
    }

    @Test
    void sameRoomTypeCodeAllowedInDifferentHotelsOfSameTenant() {
        Fixture fixture = fixture("a3");
        roomService.createRoomType(fixture.tenantId(), fixture.hotelId(), "std", "标准间", 2, 0, null);
        roomService.createRoomType(fixture.tenantId(), fixture.secondHotelId(), "std", "标准间", 2, 0, null);

        assertEquals(1, roomService.listRoomTypes(fixture.tenantId(), fixture.hotelId()).size());
        assertEquals(1, roomService.listRoomTypes(fixture.tenantId(), fixture.secondHotelId()).size());
    }

    @Test
    void roomTypeOfAnotherHotelIsNotFoundInService() {
        Fixture fixture = fixture("a4");
        RoomType otherHotelType = roomService.createRoomType(fixture.tenantId(), fixture.secondHotelId(),
                "std", "标准间", 2, 0, null);

        // 按 (tenant, hotel) 查不到 → 404，而不是"查到了再判断归属"的 400/403：
        // 后者会告诉调用方"这个房型确实存在于另一家门店"，属于跨店存在性泄露。
        assertThrows(NotFoundException.class, () -> roomService.createRoom(
                fixture.tenantId(), fixture.hotelId(), otherHotelType.id(), "0801", 8, null),
                "用 B 店的房型给 A 店建房间必须查不到");
    }

    @Test
    void roomTypeOfAnotherHotelRejectedByDatabase() {
        Fixture fixture = fixture("a5");
        RoomType otherHotelType = roomService.createRoomType(fixture.tenantId(), fixture.secondHotelId(),
                "std", "标准间", 2, 0, null);

        // 绕过服务层直接落库：复合外键必须挡住
        PhysicalRoom forged = PhysicalRoom.create(UUID.randomUUID(), fixture.tenantId(), fixture.hotelId(),
                otherHotelType.id(), "0901", 9, null, OffsetDateTime.now());

        assertThrows(DataIntegrityViolationException.class, () -> roomMapper.insertRoom(forged),
                "fk_physical_room_type_same_hotel 必须在数据库层拒绝跨店房型归属");
    }

    @Test
    void duplicateRoomCodeRejectedInSameHotelButAllowedAcrossHotels() {
        Fixture fixture = fixture("a6");
        RoomType type = roomService.createRoomType(fixture.tenantId(), fixture.hotelId(), "std", "标准间", 2, 0, null);
        RoomType otherType = roomService.createRoomType(fixture.tenantId(), fixture.secondHotelId(),
                "std", "标准间", 2, 0, null);

        roomService.createRoom(fixture.tenantId(), fixture.hotelId(), type.id(), "0801", 8, null);
        assertThrows(DataIntegrityViolationException.class, () -> roomService.createRoom(
                fixture.tenantId(), fixture.hotelId(), type.id(), "0801", 8, null),
                "uq_physical_room_code 必须挡住同店重复房号");

        // 同租户另一门店可以用相同房号
        roomService.createRoom(fixture.tenantId(), fixture.secondHotelId(), otherType.id(), "0801", 8, null);
        assertEquals(1, roomService.listRooms(fixture.tenantId(), fixture.secondHotelId(), null, null, null).size());
    }

    @Test
    void deactivatingRoomTypeBlockedWhileActiveRoomsRemain() {
        Fixture fixture = fixture("a7");
        RoomType type = roomService.createRoomType(fixture.tenantId(), fixture.hotelId(), "std", "标准间", 2, 0, null);
        PhysicalRoom room = roomService.createRoom(fixture.tenantId(), fixture.hotelId(), type.id(), "0801", 8, null);

        ConflictException conflict = assertThrows(ConflictException.class, () -> roomService.changeRoomTypeStatus(
                fixture.tenantId(), fixture.hotelId(), type.id(), OrgStatus.INACTIVE));
        assertTrue(conflict.getMessage().contains("1"), "错误信息应给出仍有多少间在用房间");

        roomService.changeRoomStatus(fixture.tenantId(), fixture.hotelId(), room.id(), RoomStatus.INACTIVE);
        RoomType deactivated = roomService.changeRoomTypeStatus(fixture.tenantId(), fixture.hotelId(),
                type.id(), OrgStatus.INACTIVE);

        assertEquals(OrgStatus.INACTIVE, deactivated.status(), "房间全部停用后房型才能停售");
    }

    @Test
    void cannotCreateRoomUnderInactiveRoomType() {
        Fixture fixture = fixture("a8");
        RoomType type = roomService.createRoomType(fixture.tenantId(), fixture.hotelId(), "std", "标准间", 2, 0, null);
        roomService.changeRoomTypeStatus(fixture.tenantId(), fixture.hotelId(), type.id(), OrgStatus.INACTIVE);

        assertThrows(ConflictException.class, () -> roomService.createRoom(
                fixture.tenantId(), fixture.hotelId(), type.id(), "0801", 8, null));
    }

    @Test
    void cannotCreateRoomInInactiveHotel() {
        Fixture fixture = fixture("a9");
        RoomType type = roomService.createRoomType(fixture.tenantId(), fixture.hotelId(), "std", "标准间", 2, 0, null);
        jdbcTemplate.update("UPDATE hotel SET status = 'INACTIVE' WHERE id = ?", fixture.hotelId());

        assertThrows(ConflictException.class, () -> roomService.createRoom(
                fixture.tenantId(), fixture.hotelId(), type.id(), "0801", 8, null),
                "停用门店不能新增房间");
    }

    @Test
    void housekeepingStatusIsIndependentFromOperationalStatus() {
        Fixture fixture = fixture("b1");
        RoomType type = roomService.createRoomType(fixture.tenantId(), fixture.hotelId(), "std", "标准间", 2, 0, null);
        PhysicalRoom room = roomService.createRoom(fixture.tenantId(), fixture.hotelId(), type.id(), "0801", 8, null);
        assertEquals(HousekeepingStatus.DIRTY, room.housekeepingStatus(), "新房间默认待清洁");

        roomService.updateRoomAttributes(fixture.tenantId(), fixture.hotelId(), room.id(), null, 9, "临街");
        roomService.changeRoomStatus(fixture.tenantId(), fixture.hotelId(), room.id(), RoomStatus.INACTIVE);

        PhysicalRoom after = roomService.requireRoom(fixture.tenantId(), fixture.hotelId(), room.id());
        assertEquals(HousekeepingStatus.DIRTY, after.housekeepingStatus(),
                "改属性与启停都不得改写房务状态——两类事实必须分开");
        assertEquals(RoomStatus.INACTIVE, after.status());
        assertEquals(9, after.floor());
        assertEquals("临街", after.notes());
    }

    @Test
    void roomAttributesUpdateBumpsVersionAndRejectsEmptyPatch() {
        Fixture fixture = fixture("b2");
        RoomType type = roomService.createRoomType(fixture.tenantId(), fixture.hotelId(), "std", "标准间", 2, 0, null);
        PhysicalRoom room = roomService.createRoom(fixture.tenantId(), fixture.hotelId(), type.id(), "0801", 8, null);

        PhysicalRoom updated = roomService.updateRoomAttributes(fixture.tenantId(), fixture.hotelId(),
                room.id(), null, null, "  靠电梯  ");

        assertEquals("靠电梯", updated.notes());
        assertEquals(room.version() + 1, updated.version(), "属性更新应递增版本号");
        assertThrows(IllegalArgumentException.class, () -> roomService.updateRoomAttributes(
                fixture.tenantId(), fixture.hotelId(), room.id(), null, null, null),
                "没有任何字段的补丁必须拒绝，不能空转一次写库");
    }

    @Test
    void listRoomsAppliesFilters() {
        Fixture fixture = fixture("b3");
        RoomType standard = roomService.createRoomType(fixture.tenantId(), fixture.hotelId(), "std", "标准间", 2, 0, null);
        RoomType deluxe = roomService.createRoomType(fixture.tenantId(), fixture.hotelId(), "dlx", "豪华间", 3, 0, null);
        PhysicalRoom r1 = roomService.createRoom(fixture.tenantId(), fixture.hotelId(), standard.id(), "0801", 8, null);
        roomService.createRoom(fixture.tenantId(), fixture.hotelId(), standard.id(), "0802", 8, null);
        roomService.createRoom(fixture.tenantId(), fixture.hotelId(), deluxe.id(), "0901", 9, null);
        roomService.changeRoomStatus(fixture.tenantId(), fixture.hotelId(), r1.id(), RoomStatus.INACTIVE);

        assertEquals(3, roomService.listRooms(fixture.tenantId(), fixture.hotelId(), null, null, null).size());
        assertEquals(2, roomService.listRooms(fixture.tenantId(), fixture.hotelId(), standard.id(), null, null).size());
        assertEquals(1, roomService.listRooms(fixture.tenantId(), fixture.hotelId(), null, RoomStatus.INACTIVE, null).size());
        assertEquals(2, roomService.listRooms(fixture.tenantId(), fixture.hotelId(), null, null, 8).size());
        assertEquals(0, roomService.listRooms(fixture.tenantId(), fixture.hotelId(), deluxe.id(), null, 8).size());
    }

    @Test
    void hotelOfAnotherTenantLooksMissing() {
        Fixture fixture = fixture("b4");
        Tenant otherTenant = organizationService.createTenant("t-other", "另一租户");

        assertThrows(NotFoundException.class,
                () -> roomService.requireHotelInTenant(otherTenant.id(), fixture.hotelId()),
                "别的租户的门店必须是 404，不是 403——否则等于确认该门店存在");
        assertTrue(roomMapper.findRoomTypes(otherTenant.id(), fixture.hotelId()).isEmpty());
    }

    @Test
    void invalidInputRejectedBeforeDatabase() {
        Fixture fixture = fixture("b5");
        RoomType type = roomService.createRoomType(fixture.tenantId(), fixture.hotelId(), "std", "标准间", 2, 0, null);

        assertThrows(IllegalArgumentException.class, () -> roomService.createRoomType(
                fixture.tenantId(), fixture.hotelId(), "非法 编码", "标准间", 2, 0, null));
        assertThrows(IllegalArgumentException.class, () -> roomService.createRoomType(
                fixture.tenantId(), fixture.hotelId(), "ok-type", "标准间", 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> roomService.createRoomType(
                fixture.tenantId(), fixture.hotelId(), "ok-type", "标准间", 2, 99, null));
        assertThrows(IllegalArgumentException.class, () -> roomService.createRoom(
                fixture.tenantId(), fixture.hotelId(), type.id(), "08 01", 8, null),
                "房号里的空白属于非法输入");
        assertThrows(IllegalArgumentException.class, () -> roomService.createRoom(
                fixture.tenantId(), fixture.hotelId(), type.id(), "0801", 999, null));
    }

    @Test
    void statusChangeIsIdempotentAndDoesNotBumpVersion() {
        Fixture fixture = fixture("b6");
        RoomType type = roomService.createRoomType(fixture.tenantId(), fixture.hotelId(), "std", "标准间", 2, 0, null);
        PhysicalRoom room = roomService.createRoom(fixture.tenantId(), fixture.hotelId(), type.id(), "0801", 8, null);

        PhysicalRoom same = roomService.changeRoomStatus(fixture.tenantId(), fixture.hotelId(),
                room.id(), RoomStatus.ACTIVE);

        assertEquals(room.version(), same.version(), "重复设置同一状态不应写库");
        RoomType sameType = roomService.changeRoomTypeStatus(fixture.tenantId(), fixture.hotelId(),
                type.id(), OrgStatus.ACTIVE);
        assertEquals(type.version(), sameType.version());
    }

    private Fixture fixture(String suffix) {
        Tenant tenant = organizationService.createTenant("t-" + suffix, "房间测试租户 " + suffix);
        Hotel hotel = organizationService.createHotel(tenant.id(), null, "h" + suffix, "主店", "Asia/Shanghai");
        Hotel second = organizationService.createHotel(tenant.id(), null, "s" + suffix, "次店", "Asia/Shanghai");
        return new Fixture(tenant.id(), hotel.id(), second.id());
    }

    private record Fixture(UUID tenantId, UUID hotelId, UUID secondHotelId) {
    }
}
