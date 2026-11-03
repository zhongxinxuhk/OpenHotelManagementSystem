package cn.sec.hn.openbackupsaas.platform.web;

import cn.sec.hn.openbackupsaas.guest.domain.VerificationChannel;
import cn.sec.hn.openbackupsaas.platform.service.PlatformChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 核验渠道配置（ADR-019 §2.6）。
 *
 * <p>对应需求："权威接口支持在管理员后台自定义设置，后续再说明对接哪些权威核验接口"。
 *
 * <p><b>必须让使用者看到的一句话</b>：本轮**没有任何 AUTHORITY 实现**。
 * 配好 endpoint 不会让核验开始调它——所以返回体里带 {@code implementationAvailable=false}，
 * 界面上也要照实说。运维按"配完就生效"去理解，会在真实住客进来时才发现所有申请
 * 都停在人工队列，而他们以为已经在走自动核验。
 */
@RestController
@RequestMapping("/api/v1/platform/verification-channels")
@Tag(name = "核验渠道配置", description = "实名核验权威接口与人工渠道的配置、熔断复位")
public class PlatformChannelController {

    /**
     * 当前代码里**没有任何权威渠道实现**。等对接具体厂商时，这里改成
     * "已注册实现的 code 集合"，配置项与实现才能对上号。
     */
    private static final java.util.Set<String> IMPLEMENTED_CODES = java.util.Set.of();

    private final PlatformChannelService channelService;

    public PlatformChannelController(PlatformChannelService channelService) {
        this.channelService = channelService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('guest.channel.read')")
    @Operation(operationId = "listVerificationChannels")
    public List<ChannelView> list() {
        return channelService.list().stream().map(ChannelView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('guest.channel.write')")
    @Operation(operationId = "createVerificationChannel")
    public ChannelView create(@Valid @RequestBody CreateChannelRequest request) {
        return ChannelView.of(channelService.create(request.code(), request.name(), request.kind(),
                request.endpoint(), request.credentialRef(),
                request.timeoutMs() == null ? 3000 : request.timeoutMs(),
                request.priority() == null ? 100 : request.priority()));
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasAuthority('guest.channel.write')")
    @Operation(operationId = "updateVerificationChannel")
    public ChannelView update(@PathVariable String code,
                              @Valid @RequestBody UpdateChannelRequest request) {
        return ChannelView.of(channelService.update(code, request.name(), request.endpoint(),
                request.credentialRef(),
                request.timeoutMs() == null ? 3000 : request.timeoutMs(),
                request.priority() == null ? 100 : request.priority(),
                request.status()));
    }

    /** 手动复位熔断。必须给原因（见服务层注释）。 */
    @PostMapping("/{code}/reset-circuit")
    @PreAuthorize("hasAuthority('guest.channel.write')")
    @Operation(operationId = "resetVerificationChannelCircuit")
    public ChannelView resetCircuit(@PathVariable String code,
                                    @Valid @RequestBody ResetCircuitRequest request) {
        return ChannelView.of(channelService.resetCircuit(code, request.reason()));
    }

    public record CreateChannelRequest(
            @NotBlank(message = "渠道编码不能为空") String code,
            @NotBlank(message = "渠道名称不能为空") String name,
            @NotNull(message = "渠道类型不能为空") VerificationChannel.ChannelKind kind,
            String endpoint,
            String credentialRef,
            Integer timeoutMs,
            Integer priority
    ) {
    }

    public record UpdateChannelRequest(
            @NotBlank(message = "渠道名称不能为空") String name,
            String endpoint,
            String credentialRef,
            Integer timeoutMs,
            Integer priority,
            @NotNull(message = "状态不能为空") VerificationChannel.ChannelStatus status
    ) {
    }

    public record ResetCircuitRequest(
            @NotBlank(message = "必须填写复位原因") String reason
    ) {
    }

    /**
     * 渠道视图。
     *
     * <p>{@code credentialRef} 可以回显（它只是引用），但**不返回凭据内容**——
     * 那张表里根本没有凭据内容，凭据在密钥管理里。
     *
     * <p>{@code implementationAvailable} 是"这个渠道现在真的能出结论吗"：
     * 人工渠道**已实现**（人工审核就是它的实现），恒为 true；
     * 权威渠道只有在代码里注册了对接实现才为 true——本轮一个都没有，
     * 所以配置界面必须照实显示"配了但没接通"，否则运维会以为配完就在走自动核验，
     * 直到真实住客进来才发现所有申请全卡在人工队列。
     */
    public record ChannelView(
            String code,
            String name,
            String kind,
            String endpoint,
            String credentialRef,
            int timeoutMs,
            int priority,
            String status,
            String circuitState,
            int consecutiveFailures,
            java.time.OffsetDateTime openedUntil,
            String lastFailureReason,
            boolean implementationAvailable
    ) {

        static ChannelView of(VerificationChannel channel) {
            boolean implemented =
                    channel.kind() == VerificationChannel.ChannelKind.MANUAL
                            || IMPLEMENTED_CODES.contains(channel.code());
            return new ChannelView(channel.code(), channel.name(), channel.kind().name(),
                    channel.endpoint(), channel.credentialRef(), channel.timeoutMs(),
                    channel.priority(), channel.status().name(), channel.circuitState().name(),
                    channel.consecutiveFailures(), channel.openedUntil(),
                    channel.lastFailureReason(), implemented);
        }
    }
}
