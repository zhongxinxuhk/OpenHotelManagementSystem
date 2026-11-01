package cn.sec.hn.openbackupsaas.tenant.web;

import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.OrgStatus;

import java.util.UUID;

/**
 * 门店对外投影。
 *
 * <p>只暴露门店标识与展示所需的档案字段，**不含** {@code tenantId}：
 * 租户归属由服务端会话推导，重复下发只会让调用方误以为可以自行指定租户。
 *
 * <p>{@code timezone} 一并返回：门店时区决定"今日"的边界，前端按它算日期，
 * 否则跨时区门店的今日到店/离店会出现差一天。
 */
public record HotelView(
        UUID id,
        String code,
        String name,
        String timezone,
        OrgStatus status
) {

    public static HotelView of(Hotel hotel) {
        return new HotelView(hotel.id(), hotel.code(), hotel.name(), hotel.timezone(), hotel.status());
    }
}
