package cn.sec.hn.openbackupsaas.room.web;

import cn.sec.hn.openbackupsaas.room.domain.RoomStatus;
import jakarta.validation.constraints.NotNull;

/** 房间启停：只接受 ACTIVE / INACTIVE。维修区间不使用本接口（S1-05 的 room_block）。 */
public record RoomStatusRequest(@NotNull(message = "状态不能为空") RoomStatus status) {
}
