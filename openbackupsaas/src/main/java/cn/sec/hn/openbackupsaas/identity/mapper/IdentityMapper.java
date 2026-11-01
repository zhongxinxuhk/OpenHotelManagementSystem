package cn.sec.hn.openbackupsaas.identity.mapper;

import cn.sec.hn.openbackupsaas.identity.domain.AppUser;
import cn.sec.hn.openbackupsaas.identity.domain.Permission;
import cn.sec.hn.openbackupsaas.identity.domain.Role;
import cn.sec.hn.openbackupsaas.identity.domain.SessionView;
import cn.sec.hn.openbackupsaas.identity.domain.UserSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 身份数据访问（S1-03）。SQL 全部在 {@code resources/mapper/identity/IdentityMapper.xml}。
 *
 * <p><b>没有不带 tenantId 的查询方法。</b>即使按主键查账号也要求同时给出 tenantId，
 * 这样"先按 id 查到再判断归属"的写法在编译期就不成立（BL-07）。
 *
 * <p>会话校验用 {@link #findUsableSession} 一条 SQL 完成三方状态判定，
 * 不提供"先查会话再查账号"的分步方法，避免出现半有效状态窗口。
 */
@Mapper
public interface IdentityMapper {

    // ---------- 账号 ----------

    int insertUser(AppUser user);

    Optional<AppUser> findUserByUsername(@Param("tenantId") UUID tenantId, @Param("username") String username);

    Optional<AppUser> findUser(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    int updateLastLogin(@Param("tenantId") UUID tenantId,
                        @Param("userId") UUID userId,
                        @Param("at") OffsetDateTime at);

    /** 改密：同时把版本号 +1，便于后续做乐观并发与审计对比。 */
    int updatePasswordHash(@Param("tenantId") UUID tenantId,
                           @Param("userId") UUID userId,
                           @Param("passwordHash") String passwordHash,
                           @Param("at") OffsetDateTime at);

    /**
     * 置"首次登录必须改密"（V13）。
     *
     * <p>单独一条语句而不是给 {@link #insertUser} 加参数：那个方法还被夹具与其它路径使用，
     * 多一个布尔参数会让"这次插入要不要强制改密"散落到每个调用点。调用点显式写一次更清楚。
     */
    int requirePasswordChange(@Param("tenantId") UUID tenantId,
                              @Param("userId") UUID userId,
                              @Param("at") OffsetDateTime at);

    // ---------- 账号管理（S3-02） ----------

    /**
     * 员工列表（分页）。
     *
     * <p>筛选条件都是可选的，但租户必带——不存在"跨租户全量列表"。
     * 返回值含 {@code password_hash}（复用 {@link AppUser} 映射），
     * 因此**调用方必须转成 View 再出接口**，绝不能把实体直接序列化出去。
     *
     * @param status   可为 null，表示不限
     * @param keyword  登录名/显示名的模糊匹配；前缀匹配用不上索引，这里刻意用 contains
     *                 （员工表规模是"每租户几十到几百人"，全表扫可接受）
     */
    List<AppUser> findUsers(@Param("tenantId") UUID tenantId,
                            @Param("status") String status,
                            @Param("keyword") String keyword,
                            @Param("limit") int limit,
                            @Param("offset") int offset);

    /** 与 {@link #findUsers} 同条件的总数；分页信封要它算总页数。 */
    long countUsers(@Param("tenantId") UUID tenantId,
                    @Param("status") String status,
                    @Param("keyword") String keyword);

    /**
     * 改账号状态。
     *
     * <p>用 {@code status <> #{status}} 做条件更新：重复调用返回 0 行而不是"成功"，
     * 让服务层能区分"真的改了"与"本来就是这个状态"（后者不必撤销会话、不必记审计）。
     */
    int updateUserStatus(@Param("tenantId") UUID tenantId,
                         @Param("userId") UUID userId,
                         @Param("status") String status,
                         @Param("at") OffsetDateTime at);

    // ---------- 角色与权限 ----------

    int insertRole(Role role);

    Optional<Role> findRoleByCode(@Param("tenantId") UUID tenantId, @Param("code") String code);

    /** 按 id 查角色，必须带租户：跨租户 id 一律查不到。 */
    Optional<Role> findRole(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /** 角色列表（S3-02），按 code 排序以保证界面顺序稳定。 */
    List<Role> findRoles(@Param("tenantId") UUID tenantId);

    /** 改角色状态。停用后该角色的权限点在下次请求即失效（见 {@link #findPermissionCodes}）。 */
    int updateRoleStatus(@Param("tenantId") UUID tenantId,
                         @Param("roleId") UUID roleId,
                         @Param("status") String status,
                         @Param("at") OffsetDateTime at);

    /**
     * 某角色已授予的权限点。
     *
     * <p>与 {@link #findPermissionCodes} 的区别：那个是"某个**人**的有效权限"
     * （已过滤停用角色）；这个是"某个**角色**登记了什么"，
     * 供管理界面展示与回填，不过滤角色状态——否则停用角色一看就"没权限了"，
     * 运维会误以为授权丢了。
     */
    List<String> findRolePermissionCodes(@Param("tenantId") UUID tenantId, @Param("roleId") UUID roleId);

    /** 撤销某个角色的一项权限。 */
    int revokeRolePermission(@Param("tenantId") UUID tenantId,
                             @Param("roleId") UUID roleId,
                             @Param("permissionCode") String permissionCode);

    /** 权限点字典（全局，不带租户）：管理界面渲染勾选项的来源。 */
    List<Permission> findAllPermissions();

    /** 员工已分配的角色（S3-02 详情展示）。 */
    List<Role> findRolesByUser(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    int grantPermission(@Param("tenantId") UUID tenantId,
                        @Param("roleId") UUID roleId,
                        @Param("permissionCode") String permissionCode);

    int assignRole(@Param("tenantId") UUID tenantId,
                   @Param("userId") UUID userId,
                   @Param("roleId") UUID roleId);

    /**
     * 清空某账号的全部角色（配合 {@link #assignRole} 做"全量替换"）。
     *
     * <p>与门店范围同理：界面上角色是多选，取消勾选必须真的失效。
     * 只做 {@code assignRole}（ON CONFLICT DO NOTHING）等于只能加不能减。
     *
     * <p>调用方必须与后续逐条插入在**同一事务**内，否则中途失败会留下
     * "角色被清空"的中间态——那个账号会瞬间失去全部权限。
     */
    int clearUserRoles(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    /** 账号的有效权限点：只算 ACTIVE 角色授予的权限，停用角色立即失效。 */
    List<String> findPermissionCodes(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    /**
     * 除某人之外，还有几个可用账号持有某个权限点（S3-02 自保护判定）。
     *
     * <p>只算 ACTIVE 账号 + ACTIVE 角色：一个被停用的账号不能作为"还有人能恢复"的依据，
     * 否则运维把同事停用后再给自己降权，结果谁也进不来。
     */
    long countOtherActiveUsersWithPermission(@Param("tenantId") UUID tenantId,
                                             @Param("permissionCode") String permissionCode,
                                             @Param("excludedUserId") UUID excludedUserId);

    // ---------- 门店数据范围 ----------

    int addHotelScope(@Param("tenantId") UUID tenantId,
                      @Param("userId") UUID userId,
                      @Param("hotelId") UUID hotelId);

    List<UUID> findHotelScopeIds(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    /**
     * 清空某账号的门店范围（配合 {@link #addHotelScope} 做"全量替换"）。
     *
     * <p>为什么要有"先清空"这个方法：界面上的门店范围是**全量替换**语义——
     * 勾掉一个门店就必须真的失效。只做 {@code addHotelScope}（ON CONFLICT DO NOTHING）
     * 等于只能加不能减，撤权会失效。
     *
     * <p>调用方必须与后续的逐条插入在**同一事务**内，否则中途失败会留下"范围被清空"
     * 的中间态——那个账号在所有门店都会 403。
     */
    int clearHotelScopes(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    // ---------- 会话 ----------

    int insertSession(UserSession session);

    /** 可用会话：未撤销、未过期、账号 ACTIVE、租户 ACTIVE，一次判定完成。 */
    Optional<SessionView> findUsableSession(@Param("tokenHash") String tokenHash);

    int revokeSession(@Param("tenantId") UUID tenantId,
                      @Param("sessionId") UUID sessionId,
                      @Param("at") OffsetDateTime at,
                      @Param("reason") String reason);

    /** 回收某账号的会话；{@code exceptSessionId} 用于"改密后保留当前会话"。 */
    int revokeUserSessions(@Param("tenantId") UUID tenantId,
                           @Param("userId") UUID userId,
                           @Param("at") OffsetDateTime at,
                           @Param("reason") String reason,
                           @Param("exceptSessionId") UUID exceptSessionId);
}
