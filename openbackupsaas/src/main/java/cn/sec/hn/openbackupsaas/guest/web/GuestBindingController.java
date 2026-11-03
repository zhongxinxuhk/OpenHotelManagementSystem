package cn.sec.hn.openbackupsaas.guest.web;

import cn.sec.hn.openbackupsaas.guest.domain.GuestCredentialView;
import cn.sec.hn.openbackupsaas.guest.domain.GuestProvider;
import cn.sec.hn.openbackupsaas.guest.security.GuestPrincipal;
import cn.sec.hn.openbackupsaas.guest.service.GuestBindingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 住客凭证绑定（ADR-019 §2.10）。
 *
 * <p>对应需求："有账户后，可以绑定其他第三方登录源"。全部接口要求已认证——
 * 绑定对象从**会话**取，不从请求体取：请求体能伪造"绑给谁"。
 *
 * <p>方式可用性跟随登录开关（实现 ∧ 控制台）：发送通道未接通时发起挑战得到 503，
 * 界面应显示"暂未开放"而不是让用户填表单。
 */
@RestController
@RequestMapping("/api/v1/guest/auth/bindings")
@Tag(name = "住客凭证绑定", description = "绑定/解绑登录方式（挑战-响应，归属验证）")
public class GuestBindingController {

    private final GuestBindingService bindingService;

    public GuestBindingController(GuestBindingService bindingService) {
        this.bindingService = bindingService;
    }

    /**
     * 发起归属挑战：向目标手机号/邮箱发送验证码。
     *
     * <p>响应**不含验证码**——码只经发送通道走（短信/邮件）。dev 信箱模式下
     * 码在服务进程内，供联调与测试取用；生产接入服务商后同理。
     */
    @PostMapping("/challenge")
    @Operation(operationId = "guestBindingChallenge")
    public ChallengeView challenge(@Valid @RequestBody ChallengeRequest request,
                                   @AuthenticationPrincipal GuestPrincipal principal) {
        var issued = bindingService.issueChallenge(principal.accountId(),
                GuestProvider.valueOf(request.provider()), request.subject());
        return new ChallengeView(issued.challengeId(), issued.provider(),
                issued.subjectMasked(), issued.expiresAt());
    }

    /** 提交验证码完成绑定。错误 5 次后挑战作废，需重新发起。 */
    @PostMapping("/confirm")
    @Operation(operationId = "guestBindingConfirm")
    public List<GuestCredentialView> confirm(@Valid @RequestBody ConfirmRequest request,
                                             @AuthenticationPrincipal GuestPrincipal principal) {
        return bindingService.confirm(principal.accountId(),
                UUID.fromString(request.challengeId()), request.code());
    }

    /** 绑定用户名（无需挑战：密码即归属证明）。 */
    @PostMapping("/username")
    @Operation(operationId = "guestBindUsername")
    public List<GuestCredentialView> bindUsername(
            @Valid @RequestBody BindUsernameRequest request,
            @AuthenticationPrincipal GuestPrincipal principal) {
        return bindingService.bindUsername(principal.accountId(),
                request.username(), request.password());
    }

    /** 我的凭证列表（本人可见完整标识）。 */
    @GetMapping
    @Operation(operationId = "listGuestBindings")
    public List<GuestCredentialView> list(@AuthenticationPrincipal GuestPrincipal principal) {
        return bindingService.credentials(principal.accountId());
    }

    /** 解绑。至少保留一个登录方式——否则账户将无法登录且无恢复通道。 */
    @DeleteMapping
    @Operation(operationId = "guestUnbind")
    public List<GuestCredentialView> unbind(
            @RequestParam String provider,
            @RequestParam String subject,
            @AuthenticationPrincipal GuestPrincipal principal) {
        return bindingService.unbind(principal.accountId(), provider, subject);
    }

    public record ChallengeRequest(
            @NotBlank(message = "登录方式不能为空") String provider,
            @NotBlank(message = "目标标识不能为空") String subject
    ) {
    }

    public record ConfirmRequest(
            @NotBlank(message = "挑战标识不能为空") String challengeId,
            @NotBlank(message = "验证码不能为空")
            @Size(min = 6, max = 6, message = "验证码为 6 位数字") String code
    ) {
    }

    public record BindUsernameRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password
    ) {
    }

    /**
     * 挑战发起结果。subject 用掩码：让用户确认"发到哪个号码了"，又不把完整标识
     * 重复暴露一遍（它本来就是用户自己刚提交的，掩码是防中途被读）。
     */
    public record ChallengeView(
            UUID challengeId,
            String provider,
            String subjectMasked,
            OffsetDateTime expiresAt
    ) {
    }
}
