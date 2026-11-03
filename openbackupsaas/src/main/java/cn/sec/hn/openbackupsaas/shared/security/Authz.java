package cn.sec.hn.openbackupsaas.shared.security;

import cn.sec.hn.openbackupsaas.shared.error.UnauthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * 授权断言（S1-03）：领域服务在**操作具体对象之前**调用，而不是在查询之后补判断。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li><b>不读请求参数。</b>租户与门店范围只来自 {@link TenantContextHolder}，
 *       调用方无法通过伪造 {@code tenantId} 改变结论。</li>
 *   <li><b>批量全有或全无。</b>{@link #requireAllHotels} 只要有一个对象越界就整体拒绝，
 *       不允许"能做的做、不能做的跳过"——那会让调用方从响应里推断出越界对象的存在。</li>
 *   <li><b>对外不解释原因。</b>抛出的 {@link AccessDeniedException} 只带通用消息，
 *       具体对象 id 只进日志（审计落库在 S1-06）。</li>
 * </ol>
 */
@Component
public class Authz {

    private static final Logger log = LoggerFactory.getLogger(Authz.class);
    private static final String DENIED_MESSAGE = "当前身份无权访问该资源";

    /** 拿不到可信身份 = 未认证（401），不是无权限（403）。 */
    public TenantContext context() {
        return TenantContextHolder.current()
                .orElseThrow(() -> new UnauthenticatedException("请求缺少可信身份"));
    }

    public UUID tenantId() {
        return context().tenantId();
    }

    public UUID userId() {
        return context().userId();
    }

    public void requireHotel(UUID hotelId) {
        TenantContext context = context();
        if (!context.coversHotel(hotelId)) {
            deny(context, "hotel", String.valueOf(hotelId));
        }
    }

    public void requireAllHotels(Collection<UUID> hotelIds) {
        if (hotelIds == null || hotelIds.isEmpty()) {
            throw new IllegalArgumentException("批量操作必须显式给出全部门店，空集合不视为全部门店");
        }
        TenantContext context = context();
        for (UUID hotelId : hotelIds) {
            if (!context.coversHotel(hotelId)) {
                deny(context, "hotel", String.valueOf(hotelId));
            }
        }
    }

    public void requirePermission(String permissionCode) {
        TenantContext context = context();
        if (!context.hasPermission(permissionCode)) {
            deny(context, "permission", permissionCode);
        }
    }

    public boolean allows(String permissionCode) {
        return TenantContextHolder.current().map(c -> c.hasPermission(permissionCode)).orElse(false);
    }

    private void deny(TenantContext context, String kind, String target) {
        // 记录"越权尝试"是必要的：谁、在哪个租户、想碰哪个对象。对外只说无权。
        log.warn("access denied tenantId={} userId={} kind={} target={}",
                context.tenantId(), context.userId(), kind, target);
        throw new AccessDeniedException(DENIED_MESSAGE);
    }
}
