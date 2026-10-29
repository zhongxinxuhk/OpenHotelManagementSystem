package cn.sec.hn.openbackupsaas.room.web;

import cn.sec.hn.openbackupsaas.tenant.domain.OrgStatus;
import jakarta.validation.constraints.NotNull;

/** 房型启停：只接受 ACTIVE / INACTIVE，其他值由枚举反序列化直接拒绝（400）。 */
public record RoomTypeStatusRequest(@NotNull(message = "状态不能为空") OrgStatus status) {
}
