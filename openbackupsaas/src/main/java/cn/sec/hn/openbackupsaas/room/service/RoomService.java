package cn.sec.hn.openbackupsaas.room.service;

import cn.sec.hn.openbackupsaas.room.domain.PhysicalRoom;
import cn.sec.hn.openbackupsaas.room.domain.RoomStatus;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.mapper.RoomMapper;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.OrgStatus;
import cn.sec.hn.openbackupsaas.tenant.mapper.OrganizationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 房型与实体房间（S1-04）。
 *
 * <p>职责边界：
 * <ul>
 *   <li>只做房型/房间自身的建立、读取、启停与属性修改；**不做**占用、可售、维修区间（S1-05 / S1-12）；</li>
 *   <li>不做租户与门店范围的判断——那由接口层用可信身份上下文完成，
 *       本服务只接受调用方给出的 {@code tenantId}，并把它作为每条 SQL 的条件；</li>
 *   <li>不写"先这样跑"的临时规则：DEC-05（未分房配额）未定案，因此房型上没有配额字段，
 *       也不提供"按房型卖 X 间"的能力。</li>
 * </ul>
 *
 * <p>状态规则（本轮可验证的部分）：
 * <ol>
 *   <li>房型下仍有 ACTIVE 房间时，不允许把房型停用——否则在用房间会指向一个停售房型，
 *       后续可售计算会自相矛盾；</li>
 *   <li>房间停用只改状态，不删行；已有历史引用（未来订单）保持不变；</li>
 *   <li>"有有效预订时停用房型/房间"的处置需要占用表（S1-05），本轮无法验证，未实现。</li>
 * </ol>
 */
@Service
public class RoomService {

    /** 与 V3 的 ck_room_type_code_fmt 保持一致。 */
    private static final Pattern TYPE_CODE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,31}$");

    /** 与 V3 的 ck_physical_room_code_fmt 保持一致；房号保留原大小写。 */
    private static final Pattern ROOM_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$");

    private static final int CAPACITY_MIN_ADULTS = 1;
    private static final int CAPACITY_MAX_ADULTS = 20;
    private static final int CAPACITY_MAX_CHILDREN = 20;
    private static final int BED_TYPE_MAX_LENGTH = 32;
    private static final int NOTES_MAX_LENGTH = 500;
    private static final int FLOOR_MIN = -10;
    private static final int FLOOR_MAX = 200;

    private final RoomMapper roomMapper;
    private final OrganizationMapper organizationMapper;
    private final Clock clock;

    public RoomService(RoomMapper roomMapper, OrganizationMapper organizationMapper, Clock clock) {
        this.roomMapper = roomMapper;
        this.organizationMapper = organizationMapper;
        this.clock = clock;
    }

    /**
     * 门店必须存在于当前租户，否则一律 404。
     *
     * <p>顺序很重要：先判"是否本租户"（404），再判"是否在我的门店范围"（403）。
     * 反过来会让调用方用 403/404 的差异探测别的租户有哪些门店。
     */
    @Transactional(readOnly = true)
    public Hotel requireHotelInTenant(UUID tenantId, UUID hotelId) {
        return organizationMapper.findHotel(tenantId, hotelId)
                .orElseThrow(() -> new NotFoundException("门店不存在：" + hotelId));
    }

    @Transactional
    public RoomType createRoomType(UUID tenantId,
                                   UUID hotelId,
                                   String code,
                                   String name,
                                   int capacityAdults,
                                   int capacityChildren,
                                   String bedType) {
        Hotel hotel = requireHotelInTenant(tenantId, hotelId);
        if (!hotel.status().allowsWrite()) {
            throw new ConflictException("门店已停用，不能新增房型：" + hotel.code());
        }
        String normalizedCode = normalizeTypeCode(code);
        String normalizedName = requireText(name, "房型名称");
        requireCapacity(capacityAdults, capacityChildren);
        String normalizedBedType = trimToNull(bedType);
        if (normalizedBedType != null && normalizedBedType.length() > BED_TYPE_MAX_LENGTH) {
            throw new IllegalArgumentException("床型长度不能超过 " + BED_TYPE_MAX_LENGTH + " 个字符");
        }

        // 编码唯一性交给 uq_room_type_code，冲突由统一异常处理转 409；不做"先查后插"以免并发下多一次往返
        RoomType roomType = RoomType.create(UUID.randomUUID(), tenantId, hotelId, normalizedCode,
                normalizedName, capacityAdults, capacityChildren, normalizedBedType, now());
        roomMapper.insertRoomType(roomType);
        return roomType;
    }

    @Transactional(readOnly = true)
    public List<RoomType> listRoomTypes(UUID tenantId, UUID hotelId) {
        requireHotelInTenant(tenantId, hotelId);
        return roomMapper.findRoomTypes(tenantId, hotelId);
    }

    @Transactional(readOnly = true)
    public RoomType requireRoomType(UUID tenantId, UUID hotelId, UUID roomTypeId) {
        return roomMapper.findRoomType(tenantId, hotelId, roomTypeId)
                .orElseThrow(() -> new NotFoundException("房型不存在：" + roomTypeId));
    }

    /**
     * 房型启停。停用前必须确认没有在用房间；重复设置同一状态视为幂等（不写库、不涨版本）。
     */
    @Transactional
    public RoomType changeRoomTypeStatus(UUID tenantId, UUID hotelId, UUID roomTypeId, OrgStatus target) {
        RoomType current = requireRoomType(tenantId, hotelId, roomTypeId);
        if (current.status() == target) {
            return current;
        }
        if (target == OrgStatus.INACTIVE) {
            int activeRooms = roomMapper.countRoomsByStatus(tenantId, hotelId, roomTypeId, RoomStatus.ACTIVE);
            if (activeRooms > 0) {
                throw new ConflictException(
                        "该房型下仍有 " + activeRooms + " 间在用房间，请先停用这些房间再停用房型");
            }
        }
        roomMapper.updateRoomTypeStatus(tenantId, hotelId, roomTypeId, target, now());
        return requireRoomType(tenantId, hotelId, roomTypeId);
    }

    @Transactional
    public PhysicalRoom createRoom(UUID tenantId,
                                   UUID hotelId,
                                   UUID roomTypeId,
                                   String code,
                                   Integer floor,
                                   String notes) {
        Hotel hotel = requireHotelInTenant(tenantId, hotelId);
        if (!hotel.status().allowsWrite()) {
            throw new ConflictException("门店已停用，不能新增房间：" + hotel.code());
        }
        RoomType roomType = requireRoomType(tenantId, hotelId, roomTypeId);
        if (!roomType.status().allowsWrite()) {
            throw new ConflictException("房型已停用，不能新增房间：" + roomType.code());
        }
        String normalizedCode = requireRoomCode(code);
        requireFloor(floor);
        String normalizedNotes = trimToNull(notes);
        requireNotesLength(normalizedNotes);

        // 跨店房型由 fk_physical_room_type_same_hotel 兜底；服务层先查一次只为给出可读错误
        PhysicalRoom room = PhysicalRoom.create(UUID.randomUUID(), tenantId, hotelId, roomTypeId,
                normalizedCode, floor, normalizedNotes, now());
        roomMapper.insertRoom(room);
        return room;
    }

    @Transactional(readOnly = true)
    public List<PhysicalRoom> listRooms(UUID tenantId, UUID hotelId, UUID roomTypeId,
                                        RoomStatus status, Integer floor) {
        requireHotelInTenant(tenantId, hotelId);
        return roomMapper.findRooms(tenantId, hotelId, roomTypeId, status, floor);
    }

    @Transactional(readOnly = true)
    public PhysicalRoom requireRoom(UUID tenantId, UUID hotelId, UUID roomId) {
        return roomMapper.findRoom(tenantId, hotelId, roomId)
                .orElseThrow(() -> new NotFoundException("房间不存在：" + roomId));
    }

    /**
     * 修改房间属性。{@code null} 表示"该字段不变"——因此本接口**不能**把备注清空成 null，
     * 这是刻意的：可空更新语义需要区分"未提供"与"置空"，等 S1-11 定义补丁语义后再扩展。
     */
    @Transactional
    public PhysicalRoom updateRoomAttributes(UUID tenantId,
                                             UUID hotelId,
                                             UUID roomId,
                                             UUID roomTypeId,
                                             Integer floor,
                                             String notes) {
        PhysicalRoom current = requireRoom(tenantId, hotelId, roomId);
        if (roomTypeId != null) {
            requireRoomType(tenantId, hotelId, roomTypeId);
        }
        requireFloor(floor);
        String normalizedNotes = trimToNull(notes);
        requireNotesLength(normalizedNotes);
        if (roomTypeId == null && floor == null && normalizedNotes == null) {
            throw new IllegalArgumentException("没有需要修改的字段");
        }
        roomMapper.updateRoomAttributes(tenantId, hotelId, roomId, roomTypeId, floor, normalizedNotes, now());
        return requireRoom(tenantId, hotelId, current.id());
    }

    @Transactional
    public PhysicalRoom changeRoomStatus(UUID tenantId, UUID hotelId, UUID roomId, RoomStatus target) {
        PhysicalRoom current = requireRoom(tenantId, hotelId, roomId);
        if (current.status() == target) {
            return current;
        }
        roomMapper.updateRoomStatus(tenantId, hotelId, roomId, target, now());
        return requireRoom(tenantId, hotelId, roomId);
    }

    private static void requireCapacity(int adults, int children) {
        if (adults < CAPACITY_MIN_ADULTS || adults > CAPACITY_MAX_ADULTS) {
            throw new IllegalArgumentException(
                    "成人数必须在 " + CAPACITY_MIN_ADULTS + "–" + CAPACITY_MAX_ADULTS + " 之间");
        }
        if (children < 0 || children > CAPACITY_MAX_CHILDREN) {
            throw new IllegalArgumentException("儿童数必须在 0–" + CAPACITY_MAX_CHILDREN + " 之间");
        }
    }

    private static void requireFloor(Integer floor) {
        if (floor != null && (floor < FLOOR_MIN || floor > FLOOR_MAX)) {
            throw new IllegalArgumentException("楼层必须在 " + FLOOR_MIN + "–" + FLOOR_MAX + " 之间");
        }
    }

    private static void requireNotesLength(String notes) {
        if (notes != null && notes.length() > NOTES_MAX_LENGTH) {
            throw new IllegalArgumentException("备注不能超过 " + NOTES_MAX_LENGTH + " 个字符");
        }
    }

    private static String normalizeTypeCode(String code) {
        String trimmed = requireText(code, "房型编码").toLowerCase(Locale.ROOT);
        if (!TYPE_CODE_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("房型编码格式不合法（小写字母或数字开头，2–32 位，可含连字符）：" + code);
        }
        return trimmed;
    }

    private static String requireRoomCode(String code) {
        String trimmed = requireText(code, "房号");
        if (!ROOM_CODE_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("房号格式不合法（字母或数字开头，1–16 位，可含 - 与 _）：" + code);
        }
        return trimmed;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
