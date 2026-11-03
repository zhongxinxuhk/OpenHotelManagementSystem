package cn.sec.hn.openbackupsaas.guest.web;

import cn.sec.hn.openbackupsaas.guest.security.GuestPrincipal;
import cn.sec.hn.openbackupsaas.guest.service.GuestPasswordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 住客口令：已登录改密 + 忘记密码重置（R-20261103-01）。
 *
 * <p>三个端点分两类信任：改密要求已认证（会话 + 当前口令双证明），
 * 重置两端点匿名（EMAIL 验证码是唯一证明——忘记密码本来就没有身份）。
 *
 * <p>重置**不受 EMAIL 登录开关约束**（账号恢复 ≠ 登录方式，可用性不联动）；
 * 唯一前置是发送通道（未接通 → 503）。重置成功撤销全部会话且不自动登录——
 * 匿名流程没有"当前会话"，强制用新口令重新登录是标准姿态。
 */
@RestController
@RequestMapping("/api/v1/guest/auth")
@Tag(name = "住客口令", description = "已登录改密与忘记密码重置（住客平面）")
public class GuestPasswordController {

    private final GuestPasswordService passwordService;

    public GuestPasswordController(GuestPasswordService passwordService) {
        this.passwordService = passwordService;
    }

    /**
     * 修改口令（已登录）。
     *
     * <p>校验当前口令（会话可能是被盗的 cookie，口令是第二因子）；
     * 成功后撤销**其它**会话、保留当前——与员工/平台改密同一语义。
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "guestChangePassword")
    public void changePassword(@Valid @RequestBody PasswordChangeRequest request,
                               @AuthenticationPrincipal GuestPrincipal principal) {
        passwordService.changePassword(principal.accountId(), principal.sessionId(),
                request.currentPassword(), request.newPassword());
    }

    /**
     * 忘记密码：发起重置挑战（匿名，向邮箱发验证码）。
     *
     * <p>**枚举防护**：未注册邮箱也照发码——响应形态与已注册完全一致。
     * 邮件正文会明示"重置登录口令"（码按用途绑定，抗钓鱼）。
     */
    @PostMapping("/password-reset/challenge")
    @Operation(operationId = "guestPasswordResetChallenge")
    public ResetChallengeView requestResetChallenge(
            @Valid @RequestBody ResetChallengeRequest request) {
        var issued = passwordService.requestResetChallenge(request.email());
        return new ResetChallengeView(issued.challengeId().toString(),
                issued.subjectMasked(), issued.expiresAt());
    }

    /**
     * 忘记密码：用验证码完成重置（匿名）。
     *
     * <p>失败统一"验证码或邮箱不正确"（401）——未注册、码错、账户关闭、
     * 账户无口令凭证同文案；挑战失效（过期/超次/已消费）为 409。
     * 成功撤销全部会话，需用新口令重新登录。
     */
    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "guestPasswordReset")
    public void completeReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordService.completeReset(
                UUID.fromString(request.challengeId()), request.code(),
                request.newPassword());
    }

    // ==================== 请求/响应 ====================

    /** 已登录改密请求。 */
    public record PasswordChangeRequest(
            @NotBlank(message = "当前口令不能为空") String currentPassword,
            @NotBlank(message = "新口令不能为空") String newPassword
    ) {
    }

    /** 重置挑战发起请求。 */
    public record ResetChallengeRequest(
            @NotBlank(message = "邮箱不能为空") String email
    ) {
    }

    /** 重置挑战发起结果：challengeId + 掩码 + 过期时间（**不含验证码**）。 */
    public record ResetChallengeView(
            String challengeId,
            String subjectMasked,
            OffsetDateTime expiresAt
    ) {
    }

    /** 重置完成请求：挑战 + 验证码 + 新口令。 */
    public record PasswordResetRequest(
            @NotBlank(message = "挑战标识不能为空") String challengeId,
            @NotBlank(message = "验证码不能为空")
            @Pattern(regexp = "^\\d{6}$", message = "验证码须为 6 位数字")
            String code,
            @NotBlank(message = "新口令不能为空") String newPassword
    ) {
    }
}
