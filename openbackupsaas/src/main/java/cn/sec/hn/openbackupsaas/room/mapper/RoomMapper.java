package cn.sec.hn.openbackupsaas.room.mapper;

import cn.sec.hn.openbackupsaas.room.domain.PhysicalRoom;
import cn.sec.hn.openbackupsaas.room.domain.RoomStatus;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.tenant.domain.OrgStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 房型与房间数据访问（S1-04）。SQL 见 {@code resources/mapper/room/RoomMapper.xml}。
 *
 * <p>全部方法都要求 {@code tenantId} **且** {@code hotelId}：
 * 门店业务表的归属是二维的，只带租户会让"同租户跨门店"的越权路径成立。
 * 与身份模块一致，这里不存在"只按 id 查"的方法。
 */
@Mapper
public interface RoomMapper {

    // ---------- 房型 ----------

    int insertRoomType(RoomType roomType);

    Optional<RoomType> findRoomType(@Param("tenantId") UUID tenantId,
                                    @Param("hotelId") UUID hotelId,
                                    @Param("id") UUID id);

    Optional<RoomType> findRoomTypeByCode(@Param("tenantId") UUID tenantId,
                                          @Param("hotelId") UUID hotelId,
                                          @Param("code") String code);

    List<RoomType> findRoomTypes(@Param("tenantId") UUID tenantId, @Param("hotelId") UUID hotelId);

    int updateRoomTypeStatus(@Param("tenantId") UUID tenantId,
                             @Param("hotelId") UUID hotelId,
                             @Param("id") UUID id,
                             @Param("status") OrgStatus status,
                             @Param("at") OffsetDateTime at);

    /** 房型下仍可售的房间数：停用房型前的唯一合法依据。 */
    int countRoomsByStatus(@Param("tenantId") UUID tenantId,
                           @Param("hotelId") UUID hotelId,
                           @Param("roomTypeId") UUID roomTypeId,
                           @Param("status") RoomStatus status);

    // ---------- 房间 ----------

    int insertRoom(PhysicalRoom room);

    Optional<PhysicalRoom> findRoom(@Param("tenantId") UUID tenantId,
                                    @Param("hotelId") UUID hotelId,
                                    @Param("id") UUID id);

    Optional<PhysicalRoom> findRoomByCode(@Param("tenantId") UUID tenantId,
                                          @Param("hotelId") UUID hotelId,
                                          @Param("code") String code);

    /** 列表按门店必选、其余条件可选；不使用全表扫描式查询。 */
    List<PhysicalRoom> findRooms(@Param("tenantId") UUID tenantId,
                                 @Param("hotelId") UUID hotelId,
                                 @Param("roomTypeId") UUID roomTypeId,
                                 @Param("status") RoomStatus status,
                                 @Param("floor") Integer floor);

    int updateRoomAttributes(@Param("tenantId") UUID tenantId,
                             @Param("hotelId") UUID hotelId,
                             @Param("id") UUID id,
                             @Param("roomTypeId") UUID roomTypeId,
                             @Param("floor") Integer floor,
                             @Param("notes") String notes,
                             @Param("at") OffsetDateTime at);

    int updateRoomStatus(@Param("tenantId") UUID tenantId,
                         @Param("hotelId") UUID hotelId,
                         @Param("id") UUID id,
                         @Param("status") RoomStatus status,
                         @Param("at") OffsetDateTime at);
}
