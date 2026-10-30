package cn.sec.hn.openbackupsaas.inventory.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 一条**期望的**投影行：由权威记录重算得出的结论（S1-12）。
 *
 * <p>重建与对账共用这一种形状：
 * <ul>
 *   <li>重建：把它写进 {@code room_inventory_day}；</li>
 *   <li>对账：把它与表里现有的行逐字段比对，差异即为"投影不可信"的证据。</li>
 * </ul>
 * 复用同一次计算是刻意的——如果"重建用什么算"和"对账用什么比"是两套 SQL，
 * 那么重建出来的结果永远对得上，对账就变成了自证清白。
 */
public record ExpectedProjectionRow(
        UUID roomId,
        LocalDate stayDate,
        boolean reservationUnavailable,
        boolean blockUnavailable,
        Long sourceRoomVersion
) {

    /** 该房该晚是否可卖（两个不可用标记都为 false）。 */
    public boolean available() {
        return !reservationUnavailable && !blockUnavailable;
    }
}
