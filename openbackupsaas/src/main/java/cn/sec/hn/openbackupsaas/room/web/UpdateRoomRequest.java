package cn.sec.hn.openbackupsaas.room.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 修改房间属性：字段为 {@code null} 表示不变（补丁语义）。
 *
 * <p>因此**不能**用它把备注清空成 null。要支持"置空"需要区分"未提供"与"显式 null"，
 * 那属于 S1-11 的补丁语义定义，不在这里临时约定。
 *
 * <p>刻意没有 {@code housekeepingStatus}：清洁状态与运营状态是两类事实，
 * 混进同一个更新接口就会重演"清洁变化改写销售事实"的老问题。
 */
public record UpdateRoomRequest(
        UUID roomTypeId,

        @Min(value = -10, message = "楼层下限 -10")
        @Max(value = 200, message = "楼层上限 200")
        Integer floor,

        @Size(max = 500, message = "备注过长")
        String notes
) {
}
