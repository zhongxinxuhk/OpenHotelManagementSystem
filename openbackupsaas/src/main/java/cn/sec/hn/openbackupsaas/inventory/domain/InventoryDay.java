package cn.sec.hn.openbackupsaas.inventory.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 某房型在某一晚的余量（S1-12）。
 *
 * <p>四个数字要一起看：
 * <ul>
 *   <li>{@code totalRooms}：参与销售的房间数（只算 ACTIVE，停用房不构成供给）；</li>
 *   <li>{@code soldRooms}：被预订占用（HELD / CONFIRMED / CHECKED_IN）；</li>
 *   <li>{@code blockedRooms}：被维修/封房占用（BLOCKED）；</li>
 *   <li>{@code availableRooms}：{@code total - sold - blocked}。</li>
 * </ul>
 *
 * <p>{@code sold} 与 {@code blocked} 各自按"有效占用状态"判定，且两者对同一房间同一晚互斥
 * （room_occupancy 的排斥约束保证），所以**不会重复扣减**。这条不变量在这里只被"使用"，
 * 它的保证在数据库那侧——重建逻辑写错时，投影表的互斥 CHECK 会拦住。
 */
public record InventoryDay(
        LocalDate stayDate,
        UUID roomTypeId,
        Integer totalRooms,
        Integer soldRooms,
        Integer blockedRooms,
        Integer availableRooms
) {
}
