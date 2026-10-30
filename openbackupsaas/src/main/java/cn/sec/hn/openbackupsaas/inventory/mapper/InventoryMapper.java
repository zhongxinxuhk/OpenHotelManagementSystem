package cn.sec.hn.openbackupsaas.inventory.mapper;

import cn.sec.hn.openbackupsaas.inventory.domain.BookableRoomType;
import cn.sec.hn.openbackupsaas.inventory.domain.ExpectedProjectionRow;
import cn.sec.hn.openbackupsaas.inventory.domain.InventoryDay;
import cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow;
import cn.sec.hn.openbackupsaas.inventory.domain.StoredProjectionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 房态投影与可售查询的数据访问（S1-12）。SQL 见
 * {@code resources/mapper/inventory/InventoryMapper.xml}。
 *
 * <p>两条纪律：
 * <ol>
 *   <li><b>可售余量永远从权威记录算</b>（{@code physical_room} + {@code room_occupancy}），
 *       不读 {@code room_inventory_day}。投影滞后时最多让日历慢一拍，绝不能出现
 *       "查询说可售、下单被拒"的错位。</li>
 *   <li><b>期望投影只有一段 SQL</b>：重建与对账共用同一个 {@code <sql>} 片段。
 *       若重建与对账各写一套，重建结果永远对得上，对账就变成自证清白。</li>
 * </ol>
 */
@Mapper
public interface InventoryMapper {

    /** 按房型 × 住宿日的余量。日期由 {@code generate_series} 展开，没有占用的日子也会返回 0 售出。 */
    List<InventoryDay> findDailyAvailability(@Param("w") InventoryWindow window);

    /**
     * 整个窗口内可连续卖出的房间数，按房型分组。
     *
     * <p>判定是"该房间与窗口区间<b>完全没有有效占用交集</b>"，不是"每晚都有空房"——
     * 后者会把"01 房今晚空、明晚被占；02 房今晚被占、明晚空"算成可售，
     * 而实际上没有任何一间房能连住。
     */
    List<BookableRoomType> findBookableRooms(@Param("w") InventoryWindow window);

    /** 按权威记录重算出的期望投影（房间 × 每晚）。对账与重建共用同一段 SQL。 */
    List<ExpectedProjectionRow> computeExpectedProjection(@Param("w") InventoryWindow window);

    /** 窗口内投影表里已有的行，供逐行比对。 */
    List<StoredProjectionRow> findStoredProjection(@Param("w") InventoryWindow window);

    /** 清空窗口内的投影（重建前调用）。返回删除行数。 */
    int deleteProjection(@Param("w") InventoryWindow window);

    /**
     * 用权威记录重建窗口内的投影（一条 INSERT ... SELECT ... ON CONFLICT）。
     *
     * <p>不用"先查出期望行、再在应用层批量 upsert"：那要多一次往返、还要自己分批，
     * 而 rebuilter 一旦在两次往返之间被别的进程看见"清空后未写入"的空窗，
     * 日历会瞬间显示全店可售。一条语句写到底，中间状态对读事务不可见。
     *
     * @return 写入（含更新）的行数
     */
    int rebuildProjection(@Param("w") InventoryWindow window);

    /**
     * 流水与占用不符的行：{@code inventory_ledger} 的净值与当前占用状态对不上。
     *
     * <p>只核预订类流水（{@code effect_kind = 'RESERVATION'}）。维修类流水的核对属
     * S2-05 内部对账，本轮不混进来——把两类混在一张报告里，会让人分不清"是卖错了还是修错了"。
     */
    List<LedgerMismatch> findLedgerMismatches(@Param("w") InventoryWindow window);

    /** 流水核对结果：某房某晚的流水净值与"是否被占"的期望不一致。 */
    record LedgerMismatch(java.util.UUID roomId, java.time.LocalDate stayDate,
                          Integer ledgerDelta, Boolean occupied) {
    }
}
