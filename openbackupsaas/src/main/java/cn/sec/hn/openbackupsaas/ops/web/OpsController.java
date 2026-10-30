package cn.sec.hn.openbackupsaas.ops.web;

import cn.sec.hn.openbackupsaas.integration.domain.ExternalTask;
import cn.sec.hn.openbackupsaas.ops.domain.DeadLetter;
import cn.sec.hn.openbackupsaas.ops.domain.OpsIntervention;
import cn.sec.hn.openbackupsaas.ops.service.OpsService;
import cn.sec.hn.openbackupsaas.shared.security.TenantContext;
import cn.sec.hn.openbackupsaas.shared.security.TenantContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 运维处置接口（S2-04）。
 *
 * <h2>为什么读写权限分开</h2>
 * {@code ops:read} 只用于盯盘（待处置队列、死信、处置记录）；{@code ops:operate} 会
 * <b>改变外部系统的执行结果</b>（重放 = 再发一次请求），必须单独授予。
 * 把两者合成一个权限，等于让"能看见队列的人"都能重放资金类调用。
 *
 * <h2>为什么操作者不从请求体取</h2>
 * 操作者来自服务端会话（{@link TenantContextHolder}），不接受客户端自报。
 * 否则留痕里的"谁干的"就变成了一句自述，审计价值归零。
 *
 * <h2>状态码</h2>
 * 目标不存在 → 404（不区分"他租户有"与"根本没有"）；状态不允许处置 → 409；
 * 缺少原因/复核 → 400（由服务抛 {@link IllegalArgumentException}，统一错误契约处理）。
 */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {

    private final OpsService opsService;

    public OpsController(OpsService opsService) {
        this.opsService = opsService;
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('ops:read')")
    public OpsService.PendingSummary pending() {
        return opsService.pending();
    }

    @GetMapping("/dead-letters")
    @PreAuthorize("hasAuthority('ops:read')")
    public List<DeadLetter> deadLetters(@RequestParam(defaultValue = "50") int limit) {
        return opsService.pendingDeadLetters(limit);
    }

    @GetMapping("/external-tasks")
    @PreAuthorize("hasAuthority('ops:read')")
    public List<ExternalTask> stuckTasks(@RequestParam(defaultValue = "DEAD") String status,
                                         @RequestParam(defaultValue = "50") int limit) {
        return opsService.tasksByStatus(status, limit);
    }

    @GetMapping("/interventions")
    @PreAuthorize("hasAuthority('ops:read')")
    public List<OpsIntervention> interventions(@RequestParam(defaultValue = "50") int limit) {
        return opsService.recentInterventions(limit);
    }

    @PostMapping("/dead-letters/{deadLetterId}/replay")
    @PreAuthorize("hasAuthority('ops:operate')")
    public ResponseEntity<OpsService.ReplayResult> replayDeadLetter(@PathVariable UUID deadLetterId,
                                                                    @RequestParam String reason) {
        TenantContext context = TenantContextHolder.require();
        return respond(opsService.replayDeadLetter(deadLetterId, context.userId(), context.username(), reason));
    }

    @PostMapping("/dead-letters/{deadLetterId}/dismiss")
    @PreAuthorize("hasAuthority('ops:operate')")
    public ResponseEntity<OpsService.ReplayResult> dismissDeadLetter(@PathVariable UUID deadLetterId,
                                                                     @RequestParam String reason) {
        TenantContext context = TenantContextHolder.require();
        return respond(opsService.dismissDeadLetter(deadLetterId, context.userId(), context.username(), reason));
    }

    @PostMapping("/external-tasks/{taskId}/replay")
    @PreAuthorize("hasAuthority('ops:operate')")
    public ResponseEntity<OpsService.ReplayResult> replayExternalTask(
            @PathVariable UUID taskId,
            @RequestParam String reason,
            @RequestParam(required = false) String reviewer) {
        TenantContext context = TenantContextHolder.require();
        return respond(opsService.replayExternalTask(taskId, context.tenantId(), context.userId(),
                context.username(), reviewer, reason));
    }

    @PostMapping("/external-tasks/{taskId}/resolve")
    @PreAuthorize("hasAuthority('ops:operate')")
    public ResponseEntity<OpsService.ReplayResult> resolveExternalTask(
            @PathVariable UUID taskId,
            @RequestParam boolean succeeded,
            @RequestParam(required = false) String resultRef,
            @RequestParam String reason) {
        TenantContext context = TenantContextHolder.require();
        return respond(opsService.resolveExternalTask(taskId, context.tenantId(), succeeded, resultRef,
                context.userId(), context.username(), reason));
    }

    private static ResponseEntity<OpsService.ReplayResult> respond(OpsService.ReplayResult result) {
        if (result.applied()) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(result.isNotFound() ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT)
                .body(result);
    }
}
