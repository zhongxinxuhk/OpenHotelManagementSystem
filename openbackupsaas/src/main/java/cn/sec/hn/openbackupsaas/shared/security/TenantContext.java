package cn.sec.hn.openbackupsaas.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * 一次请求的**可信身份上下文**（S1-03）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>租户、门店范围、权限点全部来自**服务端会话记录**，不接受任何请求参数或请求体覆盖；
 *       这是"租户来自可信身份而非请求体"的落地形式。</li>
 *   <li>不可变：创建后不能被下游代码改写，避免同一请求里出现两套归属。</li>
 *   <li>{@code hotelIds} 为空表示"没有任何门店数据权限"，**不是**"全部门店"。
 *       空集与全集必须区分，否则撤权会变成扩权。</li>
 * </ul>
 *
 * @param tenantId    租户 id，数据隔离边界
 * @param userId      员工账号 id
 * @param username    登录名，仅用于日志与展示
 * @param hotelIds    授权门店集合（不可变）
 * @param permissions 权限点集合（不可变，命名「资源:动作」）
 */
public record TenantContext(
        UUID tenantId,
        UUID userId,
        String username,
        Set<UUID> hotelIds,
        Set<String> permissions
) {

    public TenantContext {
        if (tenantId == null || userId == null) {
            throw new IllegalArgumentException("可信身份上下文缺少租户或账号");
        }
        hotelIds = hotelIds == null ? Set.of() : Set.copyOf(hotelIds);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean coversHotel(UUID hotelId) {
        return hotelId != null && hotelIds.contains(hotelId);
    }

    public boolean hasPermission(String code) {
        return code != null && permissions.contains(code);
    }
}
