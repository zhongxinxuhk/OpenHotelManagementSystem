package cn.sec.hn.openbackupsaas.reservation.mapper;

import cn.sec.hn.openbackupsaas.reservation.domain.ReservationQuery;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSummary;
import cn.sec.hn.openbackupsaas.reservation.domain.RoomAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/**
 * 订单查询侧数据访问（S1-11）。SQL 见
 * {@code resources/mapper/reservation/ReservationQueryMapper.xml}。
 *
 * <p>与 {@link ReservationMapper}（命令侧）分开，是因为两侧的关注点不同：
 * 命令侧要锁、要"读最新事实"，查询侧要"过滤下推 + 稳定排序 + 不拖全表"。
 * 混在一个 Mapper 里，迟早会有人在查询里顺手复用带 {@code FOR UPDATE} 的语句。
 */
@Mapper
public interface ReservationQueryMapper {

    /**
     * 分页查询订单摘要。
     *
     * <p>过滤条件全部下推到 SQL：不要把订单捞进内存再 filter——那会随着数据量增长
     * 变成"每翻一页扫全店"，而分页参数会让这个错误看起来只是"有点慢"。
     */
    List<ReservationSummary> findSummaries(@Param("q") ReservationQuery query);

    /** 与 {@link #findSummaries} 完全同条件的总数：条件必须来自同一段 SQL 片段，否则页码会漂。 */
    long countSummaries(@Param("q") ReservationQuery query);

    /** 订单行当前分配到的实体房（含房号），用于详情展示。 */
    List<RoomAssignment> findRoomAssignments(@Param("tenantId") UUID tenantId,
                                             @Param("hotelId") UUID hotelId,
                                             @Param("reservationId") UUID reservationId);
}
