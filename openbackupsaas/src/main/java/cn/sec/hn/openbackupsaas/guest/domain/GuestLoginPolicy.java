package cn.sec.hn.openbackupsaas.guest.domain;

import java.time.OffsetDateTime;

/**
 * 住客登录方式开关（ADR-019 §2.9）。
 *
 * <p>每行一个登录方式。**表里没有行 = 该方式不可用**（种子已为全部 8 种方式建行）。
 *
 * <p>可用性是**双条件**：{@code enabled}（本表）∧ {@code provider.isImplemented()}（代码）。
 * 本表只能关掉已实现的方式；把未实现的打开，对外仍是"未开放"。
 * 这条边界让两类变更各行其道：接通新通道永远走代码发布，暂时关停永远是控制台开关。
 */
public record GuestLoginPolicy(
        String provider,
        boolean enabled,
        OffsetDateTime updatedAt,
        String updatedBy
) {

    public static GuestLoginPolicy of(cn.sec.hn.openbackupsaas.guest.domain.GuestProvider provider,
                                      boolean enabled,
                                      OffsetDateTime updatedAt,
                                      String updatedBy) {
        return new GuestLoginPolicy(provider.name(), enabled, updatedAt, updatedBy);
    }
}
