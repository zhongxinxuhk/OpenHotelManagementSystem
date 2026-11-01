package cn.sec.hn.openbackupsaas.identity.web;

import cn.sec.hn.openbackupsaas.identity.service.AuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import cn.sec.hn.openbackupsaas.shared.security.PmsPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（S1-03）。
 *
 * <p>边界：
 * <ul>
 *   <li>只有 {@code /login} 是匿名的；其余三个都要求已认证会话。</li>
 *   <li>员工与角色的管理接口不在这里（归 S3-02）；本轮只提供身份自身的读写。</li>
 *   <li>响应体永远不含密码哈希与令牌（登录响应的 {@code token} 除外，且只出现一次）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/login")
    @Operation(operationId = "authLogin")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AuthenticationService.LoginResult result =
                authenticationService.login(request.tenantCode(), request.username(), request.password());
        return new LoginResponse(result.token(), result.expiresAt(), UserView.of(result.principal()));
    }

    @PostMapping("/logout")
    @Operation(operationId = "authLogout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal PmsPrincipal principal) {
        authenticationService.logout(principal);
    }

    @GetMapping("/me")
    @Operation(operationId = "authMe")
    public UserView me(@AuthenticationPrincipal PmsPrincipal principal) {
        return UserView.of(principal);
    }

    @PostMapping("/password")
    @Operation(operationId = "authChangePassword")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal PmsPrincipal principal,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        authenticationService.changePassword(principal, request.currentPassword(), request.newPassword());
        // 改密后当前会话仍有效，但权限与门店范围可能已被调整，客户端应重新拉 /me 而不是复用旧快照。
        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .header("X-Reauthenticate", "true")
                .build();
    }
}
