package cn.sec.hn.openbackupsaas.command.web;

import cn.sec.hn.openbackupsaas.command.service.IdempotencyService;
import cn.sec.hn.openbackupsaas.shared.security.Authz;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 原交易结果查询（S1-07 / D-10）：提交成功但响应丢失时的恢复路径。
 *
 * <p>两条入口各有用途：
 * <ul>
 *   <li>按 {@code operationId} 查——日志里能拿到操作标识时用；</li>
 *   <li>按幂等键查——客户端只知道自己的键时用。</li>
 * </ul>
 * 两种查询都在租户内收窄，查不到就是 404，不解释"是不是在别的租户"。
 */
@RestController
@RequestMapping("/api/v1/operations")
public class OperationController {

    private final IdempotencyService idempotencyService;
    private final Authz authz;

    public OperationController(IdempotencyService idempotencyService, Authz authz) {
        this.idempotencyService = idempotencyService;
        this.authz = authz;
    }

    @GetMapping("/{operationId}")
    @PreAuthorize("hasAuthority('operation:read')")
    public OperationView byOperation(@PathVariable UUID operationId) {
        UUID tenantId = authz.tenantId();
        return OperationView.of(idempotencyService.requireOperation(tenantId, operationId));
    }

    @GetMapping("/lookup")
    @PreAuthorize("hasAuthority('operation:read')")
    public OperationView byKey(@RequestParam UUID hotelId,
                               @RequestParam String namespace,
                               @RequestParam String command,
                               @RequestParam String key) {
        UUID tenantId = authz.tenantId();
        authz.requireHotel(hotelId);
        return idempotencyService.findByScope(tenantId, hotelId, namespace, command, key)
                .map(OperationView::of)
                .orElseThrow(() -> new cn.sec.hn.openbackupsaas.shared.error.NotFoundException(
                        "未找到该幂等键对应的操作：" + key));
    }
}
