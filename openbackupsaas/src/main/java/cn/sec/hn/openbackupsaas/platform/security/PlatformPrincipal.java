package cn.sec.hn.openbackupsaas.platform.security;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 平台认证结果（ADR-019 §2.3）。
 *
 * <p>与 {@code PmsPrincipal}（员工）同为"带权限的雇员"，但两者刻意不共用类型：
 * <ul>
 *   <li>{@code actions} 是平台动作码（{@code guest.verification.review}），
 *       员工那边是"资源:动作"权限点（{@code reservation:write}）——两套字典，
 *       混在一个 {@code Set<String> permissions} 里就再也分不出哪个能给谁;</li>
 *   <li>平台主体没有 {@code tenantId} / {@code hotelIds}：平台不跨租户（DEC-02），
 *       它的数据范围是"系统资源"，不是"某些门店"。</li>
 * </ul>
 * 类型不同，写错就是编译不过，而不是运行期才发现"给平台账号授了个租户权限点"。
 */
public record PlatformPrincipal(
        UUID sessionId,
        UUID userId,
        String username,
        String displayName,
        boolean mustChangePassword,
        Set<String> actions,
        OffsetDateTime sessionExpiresAt
) implements Principal {

    @Override
    public String getName() {
        return username;
    }

    public boolean hasAction(String action) {
        return actions.contains(action);
    }
}
