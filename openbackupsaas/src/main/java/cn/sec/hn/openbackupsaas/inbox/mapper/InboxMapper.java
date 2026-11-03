package cn.sec.hn.openbackupsaas.inbox.mapper;

import cn.sec.hn.openbackupsaas.inbox.domain.InboxMessage;
import cn.sec.hn.openbackupsaas.inbox.domain.InboxOutcome;
import cn.sec.hn.openbackupsaas.inbox.domain.InboxPosition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/**
 * Inbox 台账与位点的数据访问（S2-02）。SQL 见 {@code resources/mapper/inbox/InboxMapper.xml}。
 *
 * <p><b>为什么"认领"是一次 INSERT 而不是先查后插：</b>先查后插在两个消费者并发处理同一事件时
 * 会双双查到"没有"，然后双双应用。唯一键 + {@code ON CONFLICT DO NOTHING} 让并发收敛成
 * "一个插入成功、另一个拿到 0 行"，不需要分布式锁。
 */
@Mapper
public interface InboxMapper {

    /**
     * 认领并落结论。返回 0 表示这条消息已被处理过（重复投递）。
     *
     * <p>结论随插入一次写入：处理结论在事务提交前不会对外可见，
     * 因此不需要"先占位再改写"的中间态——那种中间态一旦被别的事务看见就是脏数据。
     */
    int recordIfAbsent(@Param("consumerName") String consumerName,
                       @Param("m") InboxMessage message,
                       @Param("outcome") InboxOutcome outcome,
                       @Param("detail") String detail);

    /** 这条消息已记录时的结论；从未见过返回 {@code null}。用于把"重复"讲清楚，而不是只说"跳过"。 */
    String findOutcome(@Param("consumerName") String consumerName,
                       @Param("eventId") UUID eventId);

    /**
     * 订单占用的住宿日期跨度，供消费者确定要重算的窗口。
     *
     * <p>为什么放在这里而不是复用订单查询：消费者需要的是<b>日期极值</b>（两个标量），
     * 复用查询接口会连带取出房型、房号、状态等一整套用不上的数据。
     * 这条查询只在消费路径上出现，且只读，不影响订单域的任何语义。
     */
    StaySpan findStaySpan(@Param("tenantId") UUID tenantId,
                          @Param("hotelId") UUID hotelId,
                          @Param("reservationId") UUID reservationId);

    /** 当前位点；从未处理过该聚合时返回 {@code null}。 */
    InboxPosition findPosition(@Param("consumerName") String consumerName,
                               @Param("tenantId") UUID tenantId,
                               @Param("aggregateType") String aggregateType,
                               @Param("aggregateId") UUID aggregateId);

    /**
     * 推进位点。用 GREATEST 而不是直接覆盖：并发下可能出现"后写者拿的是更旧的位点"，
     * 覆盖会让位点倒退，进而把已处理的事件重新判成"新事件"。
     */
    int advancePosition(@Param("consumerName") String consumerName,
                        @Param("tenantId") UUID tenantId,
                        @Param("aggregateType") String aggregateType,
                        @Param("aggregateId") UUID aggregateId,
                        @Param("version") long version,
                        @Param("ordinal") int ordinal);

    /** 待重放的挂起事件，按聚合内顺序返回（缺口补齐后才能按序追上）。 */
    List<InboxMessage> findParked(@Param("consumerName") String consumerName,
                                  @Param("limit") int limit);

    /** 重放后改写结论（从 PARKED_GAP 转成有结论的状态）。 */
    int settleParked(@Param("consumerName") String consumerName,
                     @Param("eventId") UUID eventId,
                     @Param("outcome") InboxOutcome outcome,
                     @Param("detail") String detail);

    /** 挂起数量，用于告警与运维观察。 */
    long countParked(@Param("consumerName") String consumerName);

    /** 订单的住宿日期跨度；半开区间 [from, to)，无订单行时两个字段均为 {@code null}。 */
    record StaySpan(java.time.LocalDate from, java.time.LocalDate to) {
        public boolean isEmpty() {
            return from == null || to == null;
        }
    }
}
