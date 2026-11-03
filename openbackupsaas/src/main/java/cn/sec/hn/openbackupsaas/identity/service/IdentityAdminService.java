package cn.sec.hn.openbackupsaas.identity.service;

import cn.sec.hn.openbackupsaas.identity.domain.AppUser;
import cn.sec.hn.openbackupsaas.identity.domain.Permission;
import cn.sec.hn.openbackupsaas.identity.domain.Role;
import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import cn.sec.hn.openbackupsaas.tenant.domain.OrgStatus;
import cn.sec.hn.openbackupsaas.tenant.mapper.OrganizationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 员工与角色管理（S3-02）。
 *
 * <h2>与 {@link AuthenticationService} 的分工</h2>
 * 那个服务只回答"你是谁、你能不能进来"；本服务回答"管理员怎么改别人"。
 * 混在一起会让认证路径被迫依赖一堆与登录无关的写方法，
 * 也让"哪些操作会影响在场会话"变得难以审计。
 *
 * <h2>本类内建的四条硬约束</h2>
 * <ol>
 *   <li><b>不能停用自己。</b>否则管理员会把自己锁在门外——而且系统里没有第二个入口
 *       能恢复（平台运维身份 A-05 因 DEC-02 未定案仍是 BLOCKED）。
 *       这条必须在服务层拦，不能只靠前端不显示按钮。</li>
 *   <li><b>停用账号即撤销其全部会话。</b>"停用"如果只是禁止新登录，
 *       已登录的设备继续可用，那它就不是停用。权限虽在下次请求实时查库，
 *       但 {@code findUsableSession} 只校验账号/租户状态，
 *       会话本身不撤销的话，被停用者仍能拿到 401 之外的部分响应路径——
 *       显式撤销更彻底，也不依赖对每条查询的推理。</li>
 *   <li><b>门店范围是全量替换。</b>界面勾掉一个门店就必须真的失效。
 *       只 upsert 等于只能加不能减，撤权会静默失效。</li>
 *   <li><b>跨租户一律 404。</b>不区分"他租户有"与"根本没有"——
 *       区分本身就是信息泄漏（能用来探测别的租户是否存在某个 id）。</li>
 * </ol>
 *
 * <h2>谁能调这些方法</h2>
 * 本类不做权限判断，由控制器上的 {@code @PreAuthorize} 决定
 * （{@code identity:read} 读、{@code identity:write} 写）。
 * 这是有意的分层：服务层管业务不变量，授权入口只有一处，便于审阅。
 */
@Service
public class IdentityAdminService {

    private static final Logger log = LoggerFactory.getLogger(IdentityAdminService.class);

    /** 与迁移 V2 的 {@code ck_role_code_fmt} 保持一致；两处不一致时以数据库为准。 */
    private static final Pattern ROLE_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");

    /**
     * 与迁移 V2 的 {@code ck_app_user_username_fmt} 保持一致。
     *
     * <p>登录名会出现在日志、审计与工单里，格式统一才便于检索；
     * 也避免 {@code Zhang.San} 与 {@code zhang.san} 变成两个"看起来是同一个人"的账号。
     */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");

    /** 撤销自己这个权限点会让自己再也改不回来，见 {@link #replaceUserRoles}。 */
    private static final String IDENTITY_WRITE = "identity:write";

    private static final String USER_STATUS_ACTIVE = "ACTIVE";
    private static final String USER_STATUS_DISABLED = "DISABLED";

    /** 单页上限：再大就不是"翻页"而是"导出"，导出属 S3-09。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final IdentityMapper identityMapper;
    /** 门店归属校验用（跨租户门店必须报可解释的错，而不是让外键把接口打成 500）。 */
    private final OrganizationMapper organizationMapper;
    /** 初始口令落库前必须先派生，明文不落任何持久化位置。 */
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public IdentityAdminService(IdentityMapper identityMapper,
                                OrganizationMapper organizationMapper,
                                PasswordEncoder passwordEncoder,
                                Clock clock) {
        this.identityMapper = identityMapper;
        this.organizationMapper = organizationMapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    // ==================== 权限点字典 ====================

    /**
     * 全量权限点字典。
     *
     * <p>全局共享，不按租户过滤——权限点是代码常量，租户只决定"谁拿到哪些"。
     */
    @Transactional(readOnly = true)
    public List<Permission> permissions() {
        return identityMapper.findAllPermissions();
    }

    // ==================== 角色 ====================

    /**
     * 角色列表，附带各自登记的权限点。
     *
     * <p>一次查完权限点而不是让前端按角色逐个请求：角色数量是"每租户几个到十几个"，
     * N+1 次请求换来的只是更慢的首屏。
     */
    @Transactional(readOnly = true)
    public List<RoleWithPermissions> roles(UUID tenantId) {
        return identityMapper.findRoles(tenantId).stream()
                .map(role -> new RoleWithPermissions(role,
                        identityMapper.findRolePermissionCodes(tenantId, role.id())))
                .toList();
    }

    /**
     * 创建角色。
     *
     * <p>角色编码必须满足 {@code ^[A-Z][A-Z0-9_]{1,31}$}（与数据库约束一致），
     * 因为编码会出现在日志与审计里，风格混杂会让检索变得困难。
     *
     * <p>权限点必须都在字典里：数据库有外键兜底，但这里先给出可读的错误，
     * 而不是让调用方看到一条 PostgreSQL 约束违例。
     */
    @Transactional
    public RoleWithPermissions createRole(UUID tenantId, String code, String name,
                                          List<String> permissionCodes) {
        String normalizedCode = requireRoleCode(code);
        String normalizedName = requireText(name, "角色名称");

        if (identityMapper.findRoleByCode(tenantId, normalizedCode).isPresent()) {
            throw new ConflictException("角色编码已存在：" + normalizedCode);
        }

        Role role = Role.create(UUID.randomUUID(), tenantId, normalizedCode, normalizedName, now());
        identityMapper.insertRole(role);

        List<String> granted = grantPermissions(tenantId, role.id(), permissionCodes);
        log.info("role created tenantId={} roleId={} code={} permissions={}",
                tenantId, role.id(), normalizedCode, granted.size());
        return new RoleWithPermissions(role, granted);
    }

    /**
     * 启停角色。
     *
     * <p>停用后该角色的权限点在**下一次请求**即失效——权限是每请求实时查库的
     * （见 {@code AuthenticationService#authenticate}），不需要等会话过期。
     */
    @Transactional
    public RoleWithPermissions changeRoleStatus(UUID tenantId, UUID roleId, String status) {
        OrgStatus target = requireRoleStatus(status);
        Role role = identityMapper.findRole(tenantId, roleId)
                .orElseThrow(() -> new NotFoundException("角色不存在：" + roleId));

        if (role.status() == target) {
            // 幂等：已经是目标状态，不当作错误，也不重复写库。
            return new RoleWithPermissions(role,
                    identityMapper.findRolePermissionCodes(tenantId, roleId));
        }

        identityMapper.updateRoleStatus(tenantId, roleId, target.name(), now());
        log.info("role status changed tenantId={} roleId={} {} -> {}",
                tenantId, roleId, role.status(), target);

        Role updated = identityMapper.findRole(tenantId, roleId)
                .orElseThrow(() -> new NotFoundException("角色不存在：" + roleId));
        return new RoleWithPermissions(updated,
                identityMapper.findRolePermissionCodes(tenantId, roleId));
    }

    // ==================== 员工 ====================

    /**
     * 开通员工账号：一次把账号、角色、门店范围都定下来。
     *
     * <h2>初始口令由服务端生成、只出现一次</h2>
     * 明文只在返回值里出现，库内只有 BCrypt 哈希，服务端事后也取不回。
     * 配合 {@code must_change_password = true}：员工首次登录必须先改密，
     * 管理员手上那份副本从那一刻起作废。这一点决定了"这个操作到底是谁做的"能不能查清。
     *
     * <h2>为什么要求至少一个角色</h2>
     * 没有任何角色的账号能登录、能改密，却什么都做不了——它是一个"活的凭据"，
     * 存在的唯一作用是被人拿去做登录测试。宁可让开通这一步失败，
     * 也不要留下这种账号再去靠巡检发现。
     *
     * <h2>门店范围可以为空</h2>
     * 集团层面的岗位（如只做报表）确实不需要门店范围，这是合法状态。
     * 真正的约束在读写时按门店校验，不在开通时假定。
     */
    @Transactional
    public CreatedUser createUser(UUID tenantId, String username, String displayName,
                                  List<UUID> roleIds, List<UUID> hotelIds) {
        String normalizedUsername = requireUsername(username);
        String normalizedDisplayName = requireText(displayName, "显示名");

        if (identityMapper.findUserByUsername(tenantId, normalizedUsername).isPresent()) {
            throw new ConflictException("登录名已存在：" + normalizedUsername);
        }

        // 角色先解析再建账号：宁可在这里失败，也不要建出一个没有任何角色的账号。
        List<Role> roles = resolveAssignableRoles(tenantId, roleIds);
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("开通员工必须至少分配一个角色：无角色的账号能登录但什么都做不了");
        }
        List<UUID> hotels = resolveHotels(tenantId, hotelIds);

        String initialPassword = InitialPasswordGenerator.generate(normalizedUsername);
        OffsetDateTime at = now();
        AppUser user = AppUser.create(UUID.randomUUID(), tenantId, normalizedUsername,
                normalizedDisplayName, passwordEncoder.encode(initialPassword), at);
        identityMapper.insertUser(user);
        identityMapper.requirePasswordChange(tenantId, user.id(), at);

        for (Role role : roles) {
            identityMapper.assignRole(tenantId, user.id(), role.id());
        }
        for (UUID hotelId : hotels) {
            identityMapper.addHotelScope(tenantId, user.id(), hotelId);
        }

        // 只记账号与授权数量，绝不记初始口令。
        log.info("user created tenantId={} userId={} username={} roles={} hotels={}",
                tenantId, user.id(), normalizedUsername, roles.size(), hotels.size());

        return new CreatedUser(
                identityMapper.findUser(tenantId, user.id()).orElseThrow(),
                initialPassword,
                roles,
                identityMapper.findHotelScopeIds(tenantId, user.id()));
    }

    /**
     * 全量替换员工的角色。
     *
     * <h2>全量语义</h2>
     * 传进来的集合就是最终结果：取消勾选必须真的失效。只 {@code assignRole}
     * （ON CONFLICT DO NOTHING）等于只能加不能减，会让"降权"静默失败。
     * 清空与逐条插入在同一事务内，避免留下"角色被清空"的中间态。
     *
     * <h2>自保护</h2>
     * 如果改的是自己、且改完之后自己不再拥有 {@code identity:write}，
     * 而租户内又没有其他可用账号持有该权限，就会把自己锁死——
     * 系统里没有第二个入口能恢复（平台运维身份 A-05 因 DEC-02 未定案仍是 BLOCKED）。
     * 判定必须带上"别人还有没有"，否则连"两个人互相备份、其中一个主动降权"这种
     * 正常操作也会被误拦。
     */
    @Transactional
    public List<Role> replaceUserRoles(UUID tenantId, UUID actorUserId, UUID targetUserId,
                                       List<UUID> roleIds) {
        identityMapper.findUser(tenantId, targetUserId)
                .orElseThrow(() -> new NotFoundException("员工不存在：" + targetUserId));

        List<Role> roles = resolveAssignableRoles(tenantId, roleIds);

        if (actorUserId.equals(targetUserId)) {
            requireRetainsIdentityAdmin(tenantId, targetUserId, roles);
        }

        identityMapper.clearUserRoles(tenantId, targetUserId);
        for (Role role : roles) {
            identityMapper.assignRole(tenantId, targetUserId, role.id());
        }
        log.info("user roles replaced tenantId={} userId={} count={} actor={}",
                tenantId, targetUserId, roles.size(), actorUserId);
        return identityMapper.findRolesByUser(tenantId, targetUserId);
    }

    /**
     * 员工分页列表。
     *
     * <p>返回的是 {@link AppUser}（含 {@code passwordHash}），
     * 控制器**必须**转成 View 再出接口。这里不做转换是因为转换规则属于表现层，
     * 塞进服务层会让"哪些字段会外泄"变得依赖服务层的实现细节。
     */
    @Transactional(readOnly = true)
    public Page<AppUser> users(UUID tenantId, String status, String keyword, int page, int size) {
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 1);

        String normalizedStatus = status == null || status.isBlank() ? null : status.trim();
        if (normalizedStatus != null && !Set.of(USER_STATUS_ACTIVE, USER_STATUS_DISABLED)
                .contains(normalizedStatus)) {
            throw new IllegalArgumentException("账号状态只允许 ACTIVE 或 DISABLED：" + status);
        }
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();

        long total = identityMapper.countUsers(tenantId, normalizedStatus, normalizedKeyword);
        List<AppUser> items = identityMapper.findUsers(tenantId, normalizedStatus, normalizedKeyword,
                safeSize, (safePage - 1) * safeSize);
        return new Page<>(items, safePage, safeSize, total);
    }

    /**
     * 员工详情：账号 + 已分配角色 + 门店范围。
     */
    @Transactional(readOnly = true)
    public UserDetail userDetail(UUID tenantId, UUID userId) {
        AppUser user = identityMapper.findUser(tenantId, userId)
                .orElseThrow(() -> new NotFoundException("员工不存在：" + userId));
        return new UserDetail(user,
                identityMapper.findRolesByUser(tenantId, userId),
                identityMapper.findHotelScopeIds(tenantId, userId));
    }

    /**
     * 改账号状态。
     *
     * <p><b>不能停用自己</b>：把自己锁在门外之后没有任何恢复入口
     * （平台运维身份 A-05 仍是 BLOCKED）。这条在服务层拦，不能只靠前端不显示按钮。
     *
     * <p><b>停用即撤销全部会话</b>：否则"停用"只禁止新登录，
     * 已经登录的设备还能继续用。
     */
    @Transactional
    public AppUser changeUserStatus(UUID tenantId, UUID actorUserId, UUID targetUserId,
                                    String status) {
        String target = requireUserStatus(status);

        if (USER_STATUS_DISABLED.equals(target) && actorUserId.equals(targetUserId)) {
            throw new ConflictException("不能停用自己的账号：停用后将没有任何恢复入口");
        }

        AppUser user = identityMapper.findUser(tenantId, targetUserId)
                .orElseThrow(() -> new NotFoundException("员工不存在：" + targetUserId));

        if (user.status().name().equals(target)) {
            // 幂等：已经是目标状态。不撤销会话（没有状态变更可言），直接返回。
            return user;
        }

        OffsetDateTime at = now();
        identityMapper.updateUserStatus(tenantId, targetUserId, target, at);

        if (USER_STATUS_DISABLED.equals(target)) {
            int revoked = identityMapper.revokeUserSessions(tenantId, targetUserId, at,
                    "USER_DISABLED", null);
            log.info("user disabled tenantId={} userId={} sessionsRevoked={}",
                    tenantId, targetUserId, revoked);
        } else {
            log.info("user enabled tenantId={} userId={}", tenantId, targetUserId);
        }

        return identityMapper.findUser(tenantId, targetUserId)
                .orElseThrow(() -> new NotFoundException("员工不存在：" + targetUserId));
    }

    /**
     * 全量替换某员工的门店范围。
     *
     * <p><b>全量语义</b>：传进来的集合就是最终结果。传空集合表示"收回全部门店权限"
     * （该账号将无法访问任何门店的业务接口）——这是**合法**操作，不是参数错误。
     *
     * <p><b>同一事务</b>：清空与逐条插入必须原子，否则中途失败会留下
     * "范围被清空"的中间态，那个账号在所有门店都会 403。
     *
     * <p><b>门店归属显式校验</b>：不依赖复合外键 {@code fk_user_hotel_scope_hotel} 兜底。
     * 外键确实会拒绝跨租户挂接，但它抛的是 {@code DataIntegrityViolationException}，
     * 到接口层就成了 500——调用方看到的是"服务器错误"，而不是"你给了别的租户的门店"。
     * 显式查一遍能把错误变成可解释的 400，代价只是一次按主键的查询。
     */
    @Transactional
    public List<UUID> replaceHotelScopes(UUID tenantId, UUID userId, List<UUID> hotelIds) {
        // 先确认账号存在且属于本租户：不存在就 404，不往 scope 表里写垃圾。
        identityMapper.findUser(tenantId, userId)
                .orElseThrow(() -> new NotFoundException("员工不存在：" + userId));

        List<UUID> distinct = hotelIds == null ? List.of()
                : hotelIds.stream().filter(java.util.Objects::nonNull).distinct().toList();

        // 门店必须都在本租户内；跨租户的一律当"不存在"处理（不解释它属于谁）。
        for (UUID hotelId : distinct) {
            organizationMapper.findHotel(tenantId, hotelId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "门店不存在或不属于当前租户：" + hotelId));
        }

        identityMapper.clearHotelScopes(tenantId, userId);
        for (UUID hotelId : distinct) {
            identityMapper.addHotelScope(tenantId, userId, hotelId);
        }
        log.info("hotel scopes replaced tenantId={} userId={} count={}",
                tenantId, userId, distinct.size());
        return identityMapper.findHotelScopeIds(tenantId, userId);
    }

    // ==================== 内部 ====================

    /**
     * 解析并校验要分配的角色。
     *
     * <p>三条检查都不是形式主义：
     * <ul>
     *   <li>角色必须属于本租户——跨租户角色由复合外键兜底，但外键抛出的
     *       {@code DataIntegrityViolationException} 到接口层会变成 500，
     *       调用方看到的是"服务器错误"而不是"你给了别的租户的角色"。</li>
     *   <li><b>停用角色拒绝分配</b>：分配一个停用角色等于什么都没给，
     *       而界面上看起来已经"授权完成"了。宁可报错让人先启用。</li>
     *   <li>去重：同一角色重复出现没有意义，还会让日志里的数量骗人。</li>
     * </ul>
     */
    private List<Role> resolveAssignableRoles(UUID tenantId, List<UUID> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        List<UUID> distinct = roleIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        List<Role> resolved = new java.util.ArrayList<>(distinct.size());
        for (UUID roleId : distinct) {
            Role role = identityMapper.findRole(tenantId, roleId)
                    .orElseThrow(() -> new IllegalArgumentException("角色不存在或不属于当前租户：" + roleId));
            if (role.status() != OrgStatus.ACTIVE) {
                throw new IllegalArgumentException(
                        "角色已停用，无法分配：" + role.code() + "；请先启用该角色");
            }
            resolved.add(role);
        }
        return List.copyOf(resolved);
    }

    /** 门店必须都在本租户内；跨租户的一律当"不存在"（不解释它属于谁）。 */
    private List<UUID> resolveHotels(UUID tenantId, List<UUID> hotelIds) {
        if (hotelIds == null || hotelIds.isEmpty()) {
            return List.of();
        }
        List<UUID> distinct = hotelIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        for (UUID hotelId : distinct) {
            organizationMapper.findHotel(tenantId, hotelId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "门店不存在或不属于当前租户：" + hotelId));
        }
        return distinct;
    }

    /**
     * 自保护：不许把自己变成"再也改不回来"的状态。
     *
     * <p>只在"改的是自己"时检查，并且要看**别人还在不在**。
     * 只看自己会让"双管理员互相备份"的正常运维无法降权，
     * 那反而会逼出一个更糟的做法——直接改库。
     */
    private void requireRetainsIdentityAdmin(UUID tenantId, UUID userId, List<Role> newRoles) {
        boolean keepsWrite = newRoles.stream()
                .anyMatch(role -> identityMapper.findRolePermissionCodes(tenantId, role.id())
                        .contains(IDENTITY_WRITE));
        if (keepsWrite) {
            return;
        }
        long otherAdmins = identityMapper.countOtherActiveUsersWithPermission(
                tenantId, IDENTITY_WRITE, userId);
        if (otherAdmins == 0) {
            throw new ConflictException(
                    "不能撤销自己的员工与角色管理权限：当前租户没有其他可管理身份的账号，"
                            + "撤销后将没有人能恢复（请先为另一位员工授予含 identity:write 的角色）");
        }
        log.info("self demotion allowed tenantId={} userId={} otherAdmins={}",
                tenantId, userId, otherAdmins);
    }

    private static String requireUsername(String username) {
        String trimmed = requireText(username, "登录名").toLowerCase(java.util.Locale.ROOT);
        if (!USERNAME_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "登录名格式不合法（小写字母或数字开头，3–64 位，可含 . _ -）：" + username);
        }
        return trimmed;
    }

    private List<String> grantPermissions(UUID tenantId, UUID roleId, List<String> permissionCodes) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return List.of();
        }
        Set<String> dictionary = identityMapper.findAllPermissions().stream()
                .map(Permission::code)
                .collect(java.util.stream.Collectors.toSet());

        List<String> unknown = permissionCodes.stream()
                .filter(code -> code != null && !dictionary.contains(code))
                .distinct()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("权限点不存在于字典：" + String.join("、", unknown));
        }

        for (String code : permissionCodes) {
            if (code != null) {
                identityMapper.grantPermission(tenantId, roleId, code);
            }
        }
        return identityMapper.findRolePermissionCodes(tenantId, roleId);
    }

    private static String requireRoleCode(String code) {
        String trimmed = requireText(code, "角色编码").toUpperCase(java.util.Locale.ROOT);
        if (!ROLE_CODE_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "角色编码格式不合法（大写字母开头，2–32 位，可含数字与下划线）：" + code);
        }
        return trimmed;
    }

    private static OrgStatus requireRoleStatus(String status) {
        try {
            return OrgStatus.valueOf(requireText(status, "角色状态").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("角色状态只允许 ACTIVE 或 INACTIVE：" + status);
        }
    }

    private static String requireUserStatus(String status) {
        String normalized = requireText(status, "账号状态").toUpperCase(java.util.Locale.ROOT);
        if (!Set.of(USER_STATUS_ACTIVE, USER_STATUS_DISABLED).contains(normalized)) {
            throw new IllegalArgumentException("账号状态只允许 ACTIVE 或 DISABLED：" + status);
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /** 角色 + 它登记的权限点。 */
    public record RoleWithPermissions(Role role, List<String> permissionCodes) {
    }

    /** 员工详情：账号 + 已分配角色 + 门店范围。 */
    public record UserDetail(AppUser user, List<Role> roles, List<UUID> hotelIds) {
    }

    /**
     * 开通结果。
     *
     * <p>{@code initialPassword} 是**明文**，只允许写进这一次的响应体。
     * 它的 {@code toString} 被覆写掉，防止被顺手打进日志——
     * 与登录令牌同样的处理，理由也一样：日志往往比数据库更容易被翻。
     */
    public record CreatedUser(AppUser user,
                              String initialPassword,
                              List<Role> roles,
                              List<UUID> hotelIds) {

        @Override
        public String toString() {
            return "CreatedUser[username=" + user.username() + ", roles=" + roles.size()
                    + ", hotels=" + hotelIds.size() + "]";
        }
    }

    /** 分页信封；{@code total} 用 long，员工表将来会到万级。 */
    public record Page<T>(List<T> items, int page, int size, long total) {
        public int totalPages() {
            return size <= 0 ? 0 : (int) ((total + size - 1) / size);
        }
    }
}
