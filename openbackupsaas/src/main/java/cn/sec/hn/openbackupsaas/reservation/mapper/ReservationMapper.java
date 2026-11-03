package cn.sec.hn.openbackupsaas.reservation.mapper;

import cn.sec.hn.openbackupsaas.reservation.domain.Reservation;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationLine;
import cn.sec.hn.openbackupsaas.reservation.domain.RoomOccupancy;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.room.domain.PhysicalRoom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 订单与占用数据访问（S1-05）。SQL 见 {@code resources/mapper/reservation/ReservationMapper.xml}。
 *
 * <p>与前面各模块一致的规矩：查询与更新都带 {@code tenantId} + {@code hotelId}。
 * 另外两点是本模块特有的：
 * <ul>
 *   <li>{@link #lockRooms} 用 {@code FOR UPDATE} 并**按 id 升序**锁定，
 *       锁序不统一就是死锁的来源；调用方不得自己拼并行锁；</li>
 *   <li>占用冲突不在这一层判断。"先查有没有冲突再插"在并发下必然漏，
 *       正确性只由数据库的 EXCLUDE 约束提供。</li>
 * </ul>
 */
@Mapper
public interface ReservationMapper {

    int insertReservation(Reservation reservation);

    /**
     * 整个入住区间都空闲的房间（按房型、门店）。
     *
     * <p>用 {@code NOT EXISTS} 而不是"先查占用再在内存里比对"：
     * 区间重叠是数据库的判定，交给 SQL 判定才不会和应用层的半开语义走偏。
     * 有效状态集合与占用排斥谓词一致（HELD/CONFIRMED/CHECKED_IN/BLOCKED）。
     */
    List<PhysicalRoom> findAvailableRooms(@Param("tenantId") UUID tenantId,
                                          @Param("hotelId") UUID hotelId,
                                          @Param("roomTypeId") UUID roomTypeId,
                                          @Param("range") StayRange range);

    Optional<Reservation> findReservation(@Param("tenantId") UUID tenantId,
                                          @Param("hotelId") UUID hotelId,
                                          @Param("id") UUID id);

    /** 锁定订单行（FOR UPDATE）：命令入口在改其子资源前必须先锁聚合根。 */
    Optional<Reservation> lockReservation(@Param("tenantId") UUID tenantId,
                                          @Param("hotelId") UUID hotelId,
                                          @Param("id") UUID id);

    int insertLine(ReservationLine line);

    Optional<ReservationLine> findLine(@Param("tenantId") UUID tenantId,
                                       @Param("hotelId") UUID hotelId,
                                       @Param("id") UUID id);

    List<ReservationLine> findLinesByReservation(@Param("tenantId") UUID tenantId,
                                                @Param("hotelId") UUID hotelId,
                                                @Param("reservationId") UUID reservationId);

    /** 订单所有占用（按行、区间排序）。状态机命令据此翻转"仍在 from 集合"的有效占用。 */
    List<RoomOccupancy> findOccupanciesByReservation(@Param("tenantId") UUID tenantId,
                                                    @Param("hotelId") UUID hotelId,
                                                    @Param("reservationId") UUID reservationId);

    /** 推进订单行状态并递增版本（乐观版本由命令层在内存核，这里只 +1）。 */
    int updateLineStatus(@Param("tenantId") UUID tenantId,
                         @Param("hotelId") UUID hotelId,
                         @Param("id") UUID id,
                         @Param("status") cn.sec.hn.openbackupsaas.reservation.domain.LineStatus status,
                         @Param("at") OffsetDateTime at);

    /** 推进订单聚合状态并递增版本。 */
    int updateReservationStatus(@Param("tenantId") UUID tenantId,
                               @Param("hotelId") UUID hotelId,
                               @Param("id") UUID id,
                               @Param("status") cn.sec.hn.openbackupsaas.reservation.domain.ReservationStatus status,
                               @Param("at") OffsetDateTime at);

    int insertOccupancy(RoomOccupancy occupancy);

    Optional<RoomOccupancy> findOccupancy(@Param("tenantId") UUID tenantId,
                                          @Param("hotelId") UUID hotelId,
                                          @Param("id") UUID id);

    List<RoomOccupancy> findOccupanciesOfRoom(@Param("tenantId") UUID tenantId,
                                              @Param("hotelId") UUID hotelId,
                                              @Param("roomId") UUID roomId);

    int updateOccupancyStatus(@Param("tenantId") UUID tenantId,
                              @Param("hotelId") UUID hotelId,
                              @Param("id") UUID id,
                              @Param("status") cn.sec.hn.openbackupsaas.reservation.domain.OccupancyStatus status,
                              @Param("at") OffsetDateTime at);

    /**
     * 按 id 升序锁定房间资源。
     *
     * <p>锁序固定为"先聚合根、再按 id 升序锁房间"，与 S0-04 的协议一致。
     * 调用方传入的顺序无关——本方法自己排序，避免不同入口各排各的。
     */
    List<PhysicalRoom> lockRooms(@Param("tenantId") UUID tenantId,
                                 @Param("hotelId") UUID hotelId,
                                 @Param("roomIds") List<UUID> roomIds);
}
