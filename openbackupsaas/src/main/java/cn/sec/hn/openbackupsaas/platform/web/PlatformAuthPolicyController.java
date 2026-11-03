package cn.sec.hn.openbackupsaas.platform.web;

import cn.sec.hn.openbackupsaas.platform.security.PlatformPrincipal;
import cn.sec.hn.openbackupsaas.platform.service.PlatformAuthPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 住客登录方式开关（管理员控制台，ADR-019 §2.9）。
 *
 * <p>对应需求："手机号，邮箱这些登录项在管理员控制台去设置"。
 * 读与写分两个动作码，理由同实名审核：查看与变更常是不同的人。
 */
@RestController
@RequestMapping("/api/v1/platform/guest-login-policy")
@Tag(name = "住客登录方式开关", description = "各登录方式的实现状态与启用开关（管理员控制台）")
public class PlatformAuthPolicyController {

    private final PlatformAuthPolicyService policyService;

    public PlatformAuthPolicyController(PlatformAuthPolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('guest.authpolicy.read')")
    @Operation(operationId = "listGuestLoginPolicy")
    public List<PlatformAuthPolicyService.PolicyView> list() {
        return policyService.list();
    }

    /**
     * 启用/停用一个登录方式。
     *
     * <p>操作者从会话取（留痕），不从请求体取。对未实现的方式打开开关会被拒（409）：
     * 不是配置非法，而是这条开关现在不会有任何效果，先告诉操作者"该方式没接通"。
     */
    @PutMapping("/{provider}")
    @PreAuthorize("hasAuthority('guest.authpolicy.write')")
    @Operation(operationId = "setGuestLoginPolicy")
    public PlatformAuthPolicyService.PolicyView set(
            @PathVariable String provider,
            @Valid @RequestBody PolicyUpdateRequest request,
            @AuthenticationPrincipal PlatformPrincipal operator) {
        return policyService.set(provider, request.enabled(), operator.username());
    }

    public record PolicyUpdateRequest(
            @NotNull(message = "enabled 不能为空") Boolean enabled
    ) {
    }
}
