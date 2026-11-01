package cn.sec.hn.openbackupsaas.identity.web;

import cn.sec.hn.openbackupsaas.identity.domain.AppUser;
import cn.sec.hn.openbackupsaas.identity.domain.Permission;
import cn.sec.hn.openbackupsaas.identity.domain.Role;
import cn.sec.hn.openbackupsaas.identity.service.IdentityAdminService;
import cn.sec.hn.openbackupsaas.shared.web.PageView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;

import java.util.List;
import java.util.UUID;

/**
 * 员工与角色管理接口（S3-02）。
 *
 * <h2>为什么与 {@link IdentityController} 分开</h2>
 * 那个类只开了一个只读端点用于证明"权限点真的在服务端生效"。
 * 本类是完整的管理面，方法数量与依赖都完全不同，挤在一起会让那个"最小切片"
 * 的示范价值消失。
 *
 * <h2>密码哈希绝不出接口</h2>
 * 服务层返回的 {@link AppUser} 含 {@code passwordHash}，**所有响应都必须经
 * {@link IdentityUserView#of} 转换**。这条不是风格问题：BCrypt 哈希一旦外泄，
 * 离线爆破的门槛就从"猜口令"降到"跑字典"。
 *
 * <h2>权限</h2>
 * 读要 {@code identity:read}，写要 {@code identity:write}。
 * 两者分开的理由与 ops 一致：能看见员工名单不等于能改别人的账号状态。
 */
@RestController
@RequestMapping("/api/v1/identity")
public class IdentityAdminController {

    private final IdentityAdminService adminService;

    public IdentityAdminController(IdentityAdminService adminService) {
        this.adminService = adminService;
    }

    // ==================== 权限点字典 ====================

    /**
     * 权限点字典。
     *
     * <p>返回全局字典（不按租户过滤）——权限点是代码常量，
     * 租户之间只差"谁拿到哪些"。
     */
    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('identity:read')")
    @Operation(operationId = "listPermissions")
    public List<Permission> permissions() {
        return adminService.permissions();
    }

    // ==================== 角色 ====================

    /** 角色列表，含各自登记的权限点。 */
    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('identity:read')")
    @Operation(operationId = "listRoles")
    public List<RoleView> roles() {
        return adminService.roles(currentTenantId()).stream().map(RoleView::of).toList();
    }

    /** 创建角色。 */
    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('identity:write')")
    @Operation(operationId = "createRole")
    public RoleView createRole(@Valid @RequestBody CreateRoleRequest request) {
        return RoleView.of(adminService.createRole(currentTenantId(), request.code(),
                request.name(), request.permissionCodes()));
    }

    /**
     * 启停角色。
     *
     * <p>停用后该角色的权限点在**下一次请求**即失效（权限是每请求实时查库的）。
     */
    @PostMapping("/roles/{roleId}/status")
    @PreAuthorize("hasAuthority('identity:write')")
    @Operation(operationId = "changeRoleStatus")
    public RoleView changeRoleStatus(@PathVariable UUID roleId,
                                     @Valid @RequestBody StatusRequest request) {
        return RoleView.of(adminService.changeRoleStatus(currentTenantId(), roleId, request.status()));
    }

    // ==================== 员工 ====================

    /**
     * 员工列表。
     *
     * @param status  ACTIVE / DISABLED；不传表示不限
     * @param keyword 登录名或显示名的模糊匹配
     */
    @GetMapping("/users")
    @PreAuthorize("hasAuthority('identity:read')")
    @Operation(operationId = "listUsers")
    public PageView<IdentityUserView> users(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        IdentityAdminService.Page<AppUser> result =
                adminService.users(currentTenantId(), status, keyword, page, size);
        List<IdentityUserView> views = result.items().stream().map(IdentityUserView::of).toList();
        return PageView.of(views, result.page(), result.size(), result.total());
    }

    /** 员工详情：账号 + 已分配角色 + 门店范围。 */
    @GetMapping("/users/{userId}")
    @PreAuthorize("hasAuthority('identity:read')")
    @Operation(operationId = "getUserDetail")
    public UserDetailView userDetail(@PathVariable UUID userId) {
        return UserDetailView.of(adminService.userDetail(currentTenantId(), userId));
    }

    /**
     * 启停账号。
     *
     * <p>停用会**撤销该账号的全部会话**，并**禁止停用自己**
     * （把自己锁在门外后没有恢复入口）。两条都由服务层强制。
     */
    @PostMapping("/users/{userId}/status")
    @PreAuthorize("hasAuthority('identity:write')")
    @Operation(operationId = "changeUserStatus")
    public IdentityUserView changeUserStatus(@PathVariable UUID userId,
                                     @Valid @RequestBody StatusRequest request) {
        return IdentityUserView.of(adminService.changeUserStatus(currentTenantId(), currentUserId(),
                userId, request.status()));
    }

    /**
     * 开通员工账号。
     *
     * <p><b>初始口令在这一个响应里出现一次</b>：服务端只存哈希，事后无法再取回。
     * 调用方（管理界面）必须当场展示给操作者并提示"只显示这一次"；
     * 丢了只能重设口令，不能"再查一次"。
     *
     * <p>账号会被标记为待改密，员工首次登录只能先改口令。
     */
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('identity:write')")
    @Operation(operationId = "createUser")
    public CreatedUserView createUser(@Valid @RequestBody CreateUserRequest request) {
        return CreatedUserView.of(adminService.createUser(currentTenantId(), request.username(),
                request.displayName(), request.roleIds(), request.hotelIds()));
    }

    /**
     * 全量替换员工角色。
     *
     * <p><b>全量语义</b>：请求体里的集合就是最终结果，取消勾选必须真的失效。
     * 传空数组在业务上等于"收回这个人的全部权限"，
     * 但改自己时会被服务层拒绝（撤销后没人能恢复）。
     */
    @PutMapping("/users/{userId}/roles")
    @PreAuthorize("hasAuthority('identity:write')")
    @Operation(operationId = "replaceUserRoles")
    public UserRolesView replaceUserRoles(@PathVariable UUID userId,
                                          @Valid @RequestBody UserRolesRequest request) {
        return new UserRolesView(userId, adminService
                .replaceUserRoles(currentTenantId(), currentUserId(), userId, request.roleIds())
                .stream().map(RoleBriefView::of).toList());
    }

    /**
     * 全量替换员工的门店范围。
     *
     * <p><b>全量语义</b>：请求体里的集合就是最终结果。
     * 传空数组表示"收回全部门店权限"，是合法操作。
     */
    @PutMapping("/users/{userId}/hotel-scopes")
    @PreAuthorize("hasAuthority('identity:write')")
    @Operation(operationId = "replaceUserHotelScopes")
    public HotelScopesView replaceHotelScopes(@PathVariable UUID userId,
                                              @Valid @RequestBody HotelScopesRequest request) {
        return new HotelScopesView(userId,
                adminService.replaceHotelScopes(currentTenantId(), userId, request.hotelIds()));
    }

    // ==================== 内部 ====================

    private static UUID currentTenantId() {
        return cn.sec.hn.openbackupsaas.shared.security.TenantContextHolder.require().tenantId();
    }

    private static UUID currentUserId() {
        return cn.sec.hn.openbackupsaas.shared.security.TenantContextHolder.require().userId();
    }

    // ==================== 请求/响应模型 ====================

    /** 通用状态请求；角色用 ACTIVE/INACTIVE，账号用 ACTIVE/DISABLED。 */
    public record StatusRequest(@NotBlank(message = "状态不能为空") String status) {
    }

    public record CreateRoleRequest(
            @NotBlank(message = "角色编码不能为空") String code,
            @NotBlank(message = "角色名称不能为空") String name,
            List<String> permissionCodes
    ) {
    }

    public record HotelScopesRequest(
            @NotNull(message = "hotelIds 不能为 null；收回全部门店请传空数组")
            List<UUID> hotelIds
    ) {
    }

    /** 开通员工。{@code roleIds} 至少一个：无角色的账号能登录却什么都做不了。 */
    public record CreateUserRequest(
            @NotBlank(message = "登录名不能为空") String username,
            @NotBlank(message = "显示名不能为空") String displayName,
            @NotEmpty(message = "至少分配一个角色：无角色的账号能登录但什么都做不了")
            List<UUID> roleIds,
            List<UUID> hotelIds
    ) {
    }

    public record UserRolesRequest(
            @NotNull(message = "roleIds 不能为 null；清空角色请传空数组")
            List<UUID> roleIds
    ) {
    }

    /**
     * 开通结果。
     *
     * <p>{@code initialPassword} 是明文，**只在这个响应里出现一次**。
     * 字段名直白地写着 initial，是为了让任何读代码的人一眼知道它不该被持久化或转发。
     */
    public record CreatedUserView(
            IdentityUserView user,
            String initialPassword,
            List<RoleBriefView> roles,
            List<UUID> hotelIds
    ) {
        public static CreatedUserView of(IdentityAdminService.CreatedUser created) {
            return new CreatedUserView(
                    IdentityUserView.of(created.user()),
                    created.initialPassword(),
                    created.roles().stream().map(RoleBriefView::of).toList(),
                    created.hotelIds());
        }
    }

    /** 角色替换结果：返回改完之后的样子，前端不必再查一次详情。 */
    public record UserRolesView(UUID userId, List<RoleBriefView> roles) {
    }

    /**
     * 员工对外视图 —— **不含 passwordHash**。
     *
     * <p>这是哈希不出接口的唯一保证点：只要所有响应都走 {@code of()}，
     * 就不存在"某个接口顺手把实体序列化了"的可能。
     *
     * <p><b>为什么不叫 UserView</b>：认证模块已有一个 {@code UserView}
     * （{@code /auth/me} 的响应，含 permissions 与 hotelIds）。
     * 两个同名 record 会让 springdoc 生成**同名 schema**，后注册的覆盖前者——
     * 表现为前端生成的类型里 {@code /auth/me} 的 permissions/hotelIds 凭空消失，
     * 而两端代码都不报错。名字必须不同。
     */
    public record IdentityUserView(
            UUID id,
            String username,
            String displayName,
            String status,
            Long version,
            java.time.OffsetDateTime lastLoginAt,
            java.time.OffsetDateTime createdAt
    ) {
        public static IdentityUserView of(AppUser user) {
            return new IdentityUserView(user.id(), user.username(), user.displayName(), user.status().name(),
                    user.version(), user.lastLoginAt(), user.createdAt());
        }
    }

    /** 角色视图：角色档案 + 它登记的权限点。 */
    public record RoleView(
            UUID id,
            String code,
            String name,
            String status,
            Long version,
            List<String> permissionCodes
    ) {
        public static RoleView of(IdentityAdminService.RoleWithPermissions source) {
            Role role = source.role();
            return new RoleView(role.id(), role.code(), role.name(), role.status().name(),
                    role.version(), source.permissionCodes());
        }
    }

    /** 员工详情：账号 + 角色 + 门店范围。 */
    public record UserDetailView(IdentityUserView user, List<RoleBriefView> roles, List<UUID> hotelIds) {
        public static UserDetailView of(IdentityAdminService.UserDetail detail) {
            return new UserDetailView(IdentityUserView.of(detail.user()),
                    detail.roles().stream().map(RoleBriefView::of).toList(),
                    detail.hotelIds());
        }
    }

    /** 角色摘要（详情里用，不带权限点——避免一个详情响应里塞两套权限）。 */
    public record RoleBriefView(UUID id, String code, String name, String status) {
        public static RoleBriefView of(Role role) {
            return new RoleBriefView(role.id(), role.code(), role.name(), role.status().name());
        }
    }

    /** 门店范围替换结果。 */
    public record HotelScopesView(UUID userId, List<UUID> hotelIds) {
    }
}
