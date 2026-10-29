package cn.sec.hn.openbackupsaas.room.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** 创建房间：必须显式给出房型，不提供"默认房型"这种隐式行为。 */
public record CreateRoomRequest(
        @NotNull(message = "房型不能为空")
        UUID roomTypeId,

        @NotBlank(message = "房号不能为空")
        @Size(max = 16, message = "房号过长")
        String code,

        @Min(value = -10, message = "楼层下限 -10")
        @Max(value = 200, message = "楼层上限 200")
        Integer floor,

        @Size(max = 500, message = "备注过长")
        String notes
) {
}
