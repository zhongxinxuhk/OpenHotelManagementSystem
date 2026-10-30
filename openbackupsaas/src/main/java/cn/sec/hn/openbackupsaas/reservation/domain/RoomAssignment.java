package cn.sec.hn.openbackupsaas.reservation.domain;

import java.util.UUID;

/**
 * 订单行当前分配到的实体房（S1-11 详情用）。
 *
 * <p>为什么单独一个投影而不是直接返回 {@link RoomOccupancy}：
 * 占用行里有 {@code operation_id}、{@code tenant_id}、内部版本号等实现细节，
 * 对外只需要"哪间房 + 当前占用处于什么状态"。**脱敏不是把字段名改得模糊，
 * 而是根本不下发给不需要的数据**。
 *
 * @param occupancyStatus 用于让前端区分"已确认"与"仅锁定未确认"，不暴露占用 id
 */
public record RoomAssignment(UUID lineId, UUID roomId, String roomCode, OccupancyStatus occupancyStatus) {
}
