package cn.sec.hn.openbackupsaas.reservation.service;

import cn.sec.hn.openbackupsaas.reservation.domain.OccupancyStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.Reservation;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationLine;
import cn.sec.hn.openbackupsaas.reservation.domain.RoomOccupancy;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationMapper;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 占用写入与资源锁（S1-05）。
 *
 * <p><b>统一锁序（S0-04 事务协议）：</b>
 * <ol>
 *   <li>先锁聚合根 {@code reservation}（{@code FOR UPDATE}）；</li>
 *   <li>再按房间 id **升序**锁 {@code physical_room}；</li>
 *   <li>最后写 {@code room_occupancy}。</li>
 * </ol>
 * 顺序固定是为了避免两个事务交叉持锁造成死锁；升序是为了让不同入口的锁序一致。
 * 本类不提供"由调用方决定顺序"的重载。
 *
 * <p><b>不做可用性查询。</b>本层不判断"房间是否空闲"——那是投影/可售的职责（S1-12），
 * 而且查完再写在并发下毫无意义。冲突一律由数据库 EXCLUDE 约束判定，
 * 冲突结果经 {@link OccupancyConflictTranslator} 转成可对外说的结论。
 */
@Service
public class OccupancyService {

    private final ReservationMapper reservationMapper;
    private final Clock clock;

    public OccupancyService(ReservationMapper reservationMapper, Clock clock) {
        this.reservationMapper = reservationMapper;
        this.clock = clock;
    }

    /**
     * 落一条预订占用（HELD）。
     *
     * @return 落库后的占用；冲突时抛 {@link ConflictException}（409，文案来自约束名映射）
     */
    @Transactional
    public RoomOccupancy hold(UUID tenantId,
                              UUID hotelId,
                              UUID reservationId,
                              UUID lineId,
                              UUID roomId,
                              StayRange stayRange,
                              OffsetDateTime expiresAt,
                              String reasonCode) {
        requireReservation(tenantId, hotelId, reservationId);
        lockRooms(tenantId, hotelId, List.of(roomId));

        RoomOccupancy occupancy = RoomOccupancy.held(UUID.randomUUID(), tenantId, hotelId, roomId,
                reservationId, lineId, stayRange, expiresAt, reasonCode, now());
        return insertOrConflict(occupancy);
    }

    /**
     * 落一条封房/维修占用（BLOCKED）。
     *
     * <p>与 {@link #hold} 走同一张表、同一个有效状态谓词，
     * 因此"预订与维修同时提交"不可能同时拿到冲突区间——这是跨表互斥协议的落点。
     */
    @Transactional
    public RoomOccupancy block(UUID tenantId, UUID hotelId, UUID roomId, StayRange stayRange, String reasonCode) {
        lockRooms(tenantId, hotelId, List.of(roomId));

        RoomOccupancy occupancy = RoomOccupancy.block(UUID.randomUUID(), tenantId, hotelId, roomId,
                stayRange, reasonCode, now());
        return insertOrConflict(occupancy);
    }

    /** 释放/解除：只改状态，不删行。终态退出排斥谓词，房间随之可再售。 */
    @Transactional
    public RoomOccupancy changeStatus(UUID tenantId, UUID hotelId, UUID occupancyId, OccupancyStatus target) {
        RoomOccupancy current = requireOccupancy(tenantId, hotelId, occupancyId);
        if (!target.belongsTo(current.kind())) {
            throw new IllegalArgumentException("状态 " + target + " 不属于占用种类 " + current.kind());
        }
        if (current.status() == target) {
            return current;
        }
        reservationMapper.updateOccupancyStatus(tenantId, hotelId, occupancyId, target, now());
        return requireOccupancy(tenantId, hotelId, occupancyId);
    }

    /** 多房场景的资源锁入口：S1-08 的原子锁房会用它一次锁全部房间。 */
    public void lockRooms(UUID tenantId, UUID hotelId, List<UUID> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) {
            throw new IllegalArgumentException("必须显式给出需要锁定的房间");
        }
        List<UUID> locked = reservationMapper.lockRooms(tenantId, hotelId, roomIds).stream()
                .map(room -> room.id())
                .toList();
        if (locked.size() != roomIds.size()) {
            throw new NotFoundException("部分房间不存在或不属于该门店");
        }
    }

    @Transactional(readOnly = true)
    public Reservation requireReservation(UUID tenantId, UUID hotelId, UUID reservationId) {
        return reservationMapper.lockReservation(tenantId, hotelId, reservationId)
                .orElseThrow(() -> new NotFoundException("订单不存在：" + reservationId));
    }

    @Transactional(readOnly = true)
    public ReservationLine requireLine(UUID tenantId, UUID hotelId, UUID lineId) {
        return reservationMapper.findLine(tenantId, hotelId, lineId)
                .orElseThrow(() -> new NotFoundException("订单行不存在：" + lineId));
    }

    @Transactional(readOnly = true)
    public RoomOccupancy requireOccupancy(UUID tenantId, UUID hotelId, UUID occupancyId) {
        return reservationMapper.findOccupancy(tenantId, hotelId, occupancyId)
                .orElseThrow(() -> new NotFoundException("占用不存在：" + occupancyId));
    }

    @Transactional(readOnly = true)
    public List<RoomOccupancy> occupanciesOfRoom(UUID tenantId, UUID hotelId, UUID roomId) {
        return reservationMapper.findOccupanciesOfRoom(tenantId, hotelId, roomId);
    }

    private RoomOccupancy insertOrConflict(RoomOccupancy occupancy) {
        try {
            reservationMapper.insertOccupancy(occupancy);
        } catch (DataIntegrityViolationException ex) {
            throw OccupancyConflictTranslator.translate(ex);
        }
        return requireOccupancy(occupancy.tenantId(), occupancy.hotelId(), occupancy.id());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
