package cn.sec.hn.openbackupsaas.platform.web;

import cn.sec.hn.openbackupsaas.platform.security.PlatformPrincipal;
import cn.sec.hn.openbackupsaas.platform.service.PlatformAuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台登录与当前身份（ADR-019 §2.3）。
 *
 * <p>路径前缀 {@code /api/v1/platform/**} 由**独立的过滤器链**处理：
 * 员工令牌打到这里是 401，住客令牌也是 401。三个平面的隔离在安全链配置里，
 * 不靠每个方法自己判断。
 */
@RestController
@RequestMapping("/api/v1/platform/auth")
@Tag(name = "平台认证", description = "平台雇员登录与会话（独立平面）")
public class PlatformAuthController {

    private final PlatformAuthenticationService authenticationService;

    public PlatformAuthController(PlatformAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * 平台登录。
     *
     * <p>没有租户编码：平台账号是全局的，用户名唯一。这一点与员工登录不同，
     * 也是"两套身份不共用"在接口形状上的体现。
     */
    @PostMapping("/login")
    @Operation(operationId = "platformLogin")
    public PlatformLoginResponse login(@Valid @RequestBody PlatformLoginRequest request) {
        var issued = authenticationService.login(request.username().trim(), request.password());
        return new PlatformLoginResponse(issued.token(), issued.expiresAt(),
                PlatformPrincipalView.of(issued.principal()));
    }

    @GetMapping("/me")
    @Operation(operationId = "getPlatformMe")
    public PlatformPrincipalView me(@AuthenticationPrincipal PlatformPrincipal principal) {
        return PlatformPrincipalView.of(principal);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "platformLogout")
    public void logout(@AuthenticationPrincipal PlatformPrincipal principal) {
        authenticationService.logout(principal);
    }

    /**
     * 平台改密（含首次改密）。成功后清掉"必须改密"标记并撤销其它设备的会话。
     *
     * <p>在待改密状态下，本接口与 {@code /me}、{@code /logout} 是仅有的三个可用接口
     * （见 {@code PlatformPasswordChangeRequiredInterceptor}）。
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "platformChangePassword")
    public void changePassword(@Valid @RequestBody PlatformChangePasswordRequest request,
                               @AuthenticationPrincipal PlatformPrincipal principal) {
        authenticationService.changePassword(principal,
                request.currentPassword(), request.newPassword());
    }

    public record PlatformChangePasswordRequest(
            @NotBlank(message = "当前口令不能为空") String currentPassword,
            @NotBlank(message = "新口令不能为空") String newPassword
    ) {
    }

    public record PlatformLoginRequest(
            @NotBlank(message = "登录名不能为空") String username,
            @NotBlank(message = "口令不能为空") String password
    ) {
    }

    public record PlatformLoginResponse(
            String token,
            OffsetDateTime expiresAt,
            PlatformPrincipalView user
    ) {
    }

    /** 对外视图：**不含口令哈希**，也不含 userId（平台账号的 id 对外没有用途）。 */
    public record PlatformPrincipalView(
            String username,
            String displayName,
            boolean mustChangePassword,
            List<String> actions,
            OffsetDateTime sessionExpiresAt
    ) {

        static PlatformPrincipalView of(PlatformPrincipal principal) {
            List<String> actions = new ArrayList<>(principal.actions());
            actions.sort(String::compareTo);
            return new PlatformPrincipalView(principal.username(), principal.displayName(),
                    principal.mustChangePassword(), List.copyOf(actions),
                    principal.sessionExpiresAt());
        }
    }
}
