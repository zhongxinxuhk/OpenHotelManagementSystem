package cn.sec.hn.openbackupsaas.inventory.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 投影表里已有的一行（对账用）。
 *
 * <p>与 {@link ExpectedProjectionRow} 分开定义不是为了对称：两者比较时的语义不同——
 * 期望行代表"应该是这样"，存储行代表"当时写成了这样"，差异要从两个方向分别统计
 * （缺失 vs 多余），混用一个类型容易把方向搞反。
 */
public record StoredProjectionRow(
        UUID roomId,
        LocalDate stayDate,
        boolean reservationUnavailable,
        boolean blockUnavailable,
        Long sourceRoomVersion
) {
}
