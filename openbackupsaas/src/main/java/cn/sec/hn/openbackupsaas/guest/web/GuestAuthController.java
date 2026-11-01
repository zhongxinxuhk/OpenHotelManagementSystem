package cn.sec.hn.openbackupsaas.guest.web;

import cn.sec.hn.openbackupsaas.guest.domain.GuestAccount;
import cn.sec.hn.openbackupsaas.guest.domain.GuestLoginPolicy;
import cn.sec.hn.openbackupsaas.guest.domain.GuestProvider;
import cn.sec.hn.openbackupsaas.guest.mapper.GuestLoginPolicyMapper;
import cn.sec.hn.openbackupsaas.guest.security.GuestPrincipal;
import cn.sec.hn.openbackupsaas.guest.service.GuestAccountService;
import cn.sec.hn.openbackupsaas.guest.service.GuestAuthenticationService;
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
import java.util.List;

/**
 * 住客认证入口（ADR-019 §2.9）：注册、登录、登出、登录方式清单。
 *
 * <p>这些接口住在**住客安全链**里（{@code /api/v1/guest/**}），其中前三个匿名放行、
 * 登出要求已认证。住客链只认住客令牌——员工或平台令牌打到这里是 401，
 * 与业务接口一样，不需要额外的判断。
 *
 * <p>登录方式清单（{@code /methods}）是给客户端渲染登录页用的：
 * 客户端只渲染 {@code available=true} 的按钮，不在界面出现"点了才告诉你没开放"。
 */
@RestController
@RequestMapping("/api/v1/guest/auth")
@Tag(name = "住客认证", description = "注册、登录与登录方式清单（住客平面）")
public class GuestAuthController {

    private final GuestAccountService accountService;
    private final GuestAuthenticationService authenticationService;
    private final GuestLoginPolicyMapper loginPolicyMapper;

    public GuestAuthController(GuestAccountService accountService,
                               GuestAuthenticationService authenticationService,
                               GuestLoginPolicyMapper loginPolicyMapper) {
        this.accountService = accountService;
        this.authenticationService = authenticationService;
        this.loginPolicyMapper = loginPolicyMapper;
    }

    /**
     * 用户名 + 密码注册，成功即登录（返回会话）。
     *
     * <p>注册后的账户处于未实名状态（{@code realNameRequired=true}）——
     * 需求口径是"不管用什么方式注册进入，首先就要实名认证"，
     * 客户端拿到响应后应引导用户进入实名流程。
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "guestRegister")
    public GuestAuthResponse register(@Valid @RequestBody GuestRegisterRequest request) {
        GuestAccount account = accountService.registerWithUsername(
                request.username(), request.password(), request.displayName());
        var issued = authenticationService.issueSession(account.id());
        return GuestAuthResponse.of(issued, authenticationService.snapshotOf(account.id()));
    }

    /** 用户名 + 密码登录。失败统一"用户名或密码不正确"（401），不构成账号枚举器。 */
    @PostMapping("/login")
    @Operation(operationId = "guestLogin")
    public GuestAuthResponse login(@Valid @RequestBody GuestLoginRequest request) {
        var login = authenticationService.loginWithUsernamePassword(
                request.username(), request.password());
        // 登录服务在口令比对通过后才解析账户；快照在此时读取，
        // 失败路径上不会多读任何账户资料。
        return GuestAuthResponse.of(
                new GuestAuthenticationService.IssuedSession(login.token(), login.expiresAt()),
                authenticationService.snapshotOf(login.accountId()));
    }

    /** 登出：撤销当前会话。 */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "guestLogout")
    public void logout(@AuthenticationPrincipal GuestPrincipal principal) {
        // 单会话登出：只撤当前这个，不动该账户其它设备的会话——
        // "退出登录"是这台设备的事，"踢掉所有设备"是账户安全动作，语义不同。
        authenticationService.revokeSession(principal.sessionId(), "GUEST_LOGOUT");
    }

    /**
     * 登录方式清单（匿名）。
     *
     * <p>返回**全部**方式与各自状态，而不是只返回可用的：客户端需要区分
     * "未开放但将来会开"（隐藏或置灰）与"永不开放"（不显示），这需要原始数据。
     * 判断只看 {@code available}。
     */
    @GetMapping("/methods")
    @Operation(operationId = "listGuestLoginMethods")
    public List<GuestMethodView> methods() {
        return loginPolicyMapper.findAll().stream()
                .map(GuestAuthController::toView)
                .toList();
    }

    private static GuestMethodView toView(GuestLoginPolicy policy) {
        GuestProvider provider = GuestProvider.valueOf(policy.provider());
        boolean implemented = provider.isImplemented();
        return new GuestMethodView(
                policy.provider(),
                implemented && policy.enabled(),
                implemented,
                policy.enabled(),
                policy.updatedAt(),
                policy.updatedBy());
    }

    public record GuestRegisterRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password,
            String displayName
    ) {
    }

    public record GuestLoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password
    ) {
    }

    /**
     * 注册/登录响应。**不含主身份 id**（ADR-018：客户端不感知内部标识）。
     */
    public record GuestAuthResponse(
            String token,
            OffsetDateTime expiresAt,
            GuestSelfController.GuestMeView user
    ) {

        static GuestAuthResponse of(GuestAuthenticationService.IssuedSession issued,
                                    GuestAuthenticationService.GuestMeSnapshot snapshot) {
            GuestSelfController.GuestMeView me = new GuestSelfController.GuestMeView(
                    snapshot.displayName(),
                    snapshot.status().name(),
                    !snapshot.status().satisfiesRealNameRequirement(),
                    issued.expiresAt());
            return new GuestAuthResponse(issued.token(), issued.expiresAt(), me);
        }
    }

    /**
     * 登录方式状态。
     *
     * @param available 客户端唯一该看的字段：该方式现在能不能用（实现 ∧ 开关）
     * @param implemented 代码层面是否已接通（未接通时即使开关打开也不可用）
     * @param enabled 控制台开关的当前值（管理员视角）
     */
    public record GuestMethodView(
            String provider,
            boolean available,
            boolean implemented,
            boolean enabled,
            OffsetDateTime updatedAt,
            String updatedBy
    ) {
    }
}
