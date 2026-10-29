package cn.sec.hn.openbackupsaas.room.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建房型。容量默认值与数据库一致（成人 2 / 儿童 0）：
 * 不传就当两人标准间，避免"没填容量"变成 0 人房型这种无法销售的数据。
 */
public record CreateRoomTypeRequest(
        @NotBlank(message = "房型编码不能为空")
        @Size(max = 32, message = "房型编码过长")
        String code,

        @NotBlank(message = "房型名称不能为空")
        @Size(max = 64, message = "房型名称过长")
        String name,

        @Min(value = 1, message = "成人数至少 1")
        @Max(value = 20, message = "成人数最多 20")
        Integer capacityAdults,

        @Min(value = 0, message = "儿童数不能为负")
        @Max(value = 20, message = "儿童数最多 20")
        Integer capacityChildren,

        @Size(max = 32, message = "床型过长")
        String bedType
) {

    public int adultsOrTwo() {
        return capacityAdults == null ? 2 : capacityAdults;
    }

    public int childrenOrZero() {
        return capacityChildren == null ? 0 : capacityChildren;
    }
}
