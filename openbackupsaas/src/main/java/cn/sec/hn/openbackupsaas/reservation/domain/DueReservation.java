package cn.sec.hn.openbackupsaas.reservation.domain;

import java.util.UUID;

/**
 * 到期扫描的候选订单（S1-10）：只带定位与归属，不带状态结论。
 *
 * <p>为什么不在扫描结果里带上占用明细：扫描只负责"指名"，判定与写入由服务层在
 * 同一事务内重新读一次后完成。把明细带出来会让人误以为可以拿扫描时的快照直接写库——
 * 那正是"查完再改"在并发下失效的经典写法。
 *
 * @param id     订单 id
 * @param tenantId 订单所属租户；Worker 为这一行显式绑定租户上下文（B-07）
 * @param hotelId  订单所属门店
 * @param version  扫描时看到的版本，仅供日志与审计比对，不参与乐观校验
 */
public record DueReservation(UUID id, UUID tenantId, UUID hotelId, Long version) {
}
