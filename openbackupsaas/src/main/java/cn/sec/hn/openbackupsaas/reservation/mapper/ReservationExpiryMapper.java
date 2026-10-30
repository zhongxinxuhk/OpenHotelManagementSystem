package cn.sec.hn.openbackupsaas.reservation.mapper;

import cn.sec.hn.openbackupsaas.reservation.domain.DueReservation;
import cn.sec.hn.openbackupsaas.reservation.domain.HoldDueState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/**
 * 到期承接（HELD 锁房超时）的数据访问（S1-10）。SQL 见
 * {@code resources/mapper/reservation/ReservationExpiryMapper.xml}。
 *
 * <p><b>为什么单独一个 Mapper 而不是塞进 {@link ReservationMapper}：</b>
 * 本接口只有两条语句，且都是"跨门店、系统级"的语义——扫描不带 hotelId（Worker 不知道
 * 谁家有到期的单），条件更新要求"仍处于 PENDING"以免覆盖别的命令。把这两条和
 * "租户/门店内查询"混在一起，会让"这里为什么没有 hotelId 条件"变成需要考古的问题。
 *
 * <p><b>为什么没有 hotelId 条件也不越权：</b>扫描行本身携带 {@code tenant_id}，
 * 服务层为每一行显式绑定该行自己的租户上下文（B-07）后再执行后续写入；
 * 不存在"用一个租户去改另一个租户数据"的路径。
 */
@Mapper
public interface ReservationExpiryMapper {

    /**
     * 领取一批"锁房已到期且仍待处理"的订单，并用 {@code FOR UPDATE SKIP LOCKED} 占住它们。
     *
     * <p>三个过滤条件缺一不可：
     * <ol>
     *   <li>{@code status = 'PENDING'}：已确认/已取消的单不归到期任务处理。
     *       已确认单若也要超时释放，属于另一条业务规则（DEC-08 讨论范围），不能顺手放开。</li>
     *   <li><b>存在</b>已到期的 HELD 占用：没有任何 HELD 的单（例如占用已被释放但聚合状态没收口）
     *       不在本任务的职责内，扫进来只会产生"改了状态却没有占用可释放"的假动作。</li>
     *   <li><b>不存在</b>未到期的 HELD 占用：一条单有多个房间时，只有全部到期才算整单到期。
     *       整单统一迁移（DEC-04 部分履约未定案）下，不能因为其中一个房间先到期就把
     *       整单释放——那会把还没到期的房间一起放出去。</li>
     * </ol>
     *
     * <p>到期比较用的是<b>数据库的 {@code now()}</b>（事务时间），不是应用时钟：
     * 多个 Worker 分属不同机器，应用时钟之间的漂移会直接变成"谁先谁后"的业务差异。
     *
     * @param batchSize 本批上限。批越小单事务越短，但空转扫描越频繁
     * @return 已加锁的到期订单（顺序按 id，保证多 Worker 的等待顺序一致）
     */
    List<DueReservation> claimDueReservations(@Param("batchSize") int batchSize);

    /**
     * 条件迁移 {@code PENDING → EXPIRED}。
     *
     * <p>这是"领取之后、写入之前"的<b>二次校验</b>：即使这一行已经被本事务锁住，
     * 也不能假定它还是 PENDING（行锁只能挡住别人写，挡不住本事务里的判断已过期）。
     * 更实际的作用是给并发入口一个确定信号：返回 0 表示"别人先处理了"，
     * 服务层据此跳过而不是硬写。
     *
     * @return 1 表示本次真的完成了迁移；0 表示状态已不是 PENDING
     */
    int expireIfPending(@Param("tenantId") UUID tenantId,
                        @Param("hotelId") UUID hotelId,
                        @Param("id") UUID id);

    /**
     * 复核一条订单的 HELD 占用是否"全部到期"（用数据库时钟）。
     *
     * <p>扫描条件在 SQL 里已判过一次，这里再判一次不是重复劳动：
     * 扫描与逐单处理之间隔了别的行的处理，而<b>正确性不能依赖"中间什么都没发生"这个假设</b>。
     * 判断放在数据库侧复用同一个 {@code now()}，是为了不让应用时钟参与"是否到期"的结论。
     */
    HoldDueState checkHoldDue(@Param("tenantId") UUID tenantId,
                              @Param("hotelId") UUID hotelId,
                              @Param("reservationId") UUID reservationId);
}
