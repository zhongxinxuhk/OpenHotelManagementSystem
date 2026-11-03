package cn.sec.hn.openbackupsaas.identity.web;

import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import io.swagger.v3.oas.annotations.Operation;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import cn.sec.hn.openbackupsaas.shared.security.Authz;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 身份只读接口（S1-03 最小切片）。
 *
 * <p>只开一个只读端点，用来证明两件事同时成立：
 * <ol>
 *   <li>权限点真的在服务端生效（没有 {@code identity:read} 就 403，前端隐藏菜单不算数）；</li>
 *   <li>租户边界真的按可信身份收窄（目标账号属于别的租户时返回 404，而不是 403 或空数组——
 *       空数组会让调用方误以为"该员工没有任何门店范围"）。</li>
 * </ol>
 *
 * <p>员工与角色的增删改属于 S3-02，不在本轮范围。
 */
@RestController
@RequestMapping("/api/v1/identity")
public class IdentityController {

    private final IdentityMapper identityMapper;
    private final Authz authz;

    public IdentityController(IdentityMapper identityMapper, Authz authz) {
        this.identityMapper = identityMapper;
        this.authz = authz;
    }

    @GetMapping("/users/{userId}/hotel-scopes")
    @Operation(operationId = "identityHotelScopes")
    @PreAuthorize("hasAuthority('identity:read')")
    public List<UUID> hotelScopes(@PathVariable UUID userId) {
        // 租户来自可信身份上下文；此处不接受任何 tenantId 参数，也就无从伪造
        UUID tenantId = authz.tenantId();
        identityMapper.findUser(tenantId, userId)
                .orElseThrow(() -> new NotFoundException("员工不存在：" + userId));
        return identityMapper.findHotelScopeIds(tenantId, userId);
    }
}
