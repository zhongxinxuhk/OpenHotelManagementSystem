package cn.sec.hn.openbackupsaas.identity.mapper;

import cn.sec.hn.openbackupsaas.identity.domain.AppUser;
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

    // ---------- 角色与权限 ----------

    int insertRole(Role role);

    Optional<Role> findRoleByCode(@Param("tenantId") UUID tenantId, @Param("code") String code);

    int grantPermission(@Param("tenantId") UUID tenantId,
                        @Param("roleId") UUID roleId,
                        @Param("permissionCode") String permissionCode);

    int assignRole(@Param("tenantId") UUID tenantId,
                   @Param("userId") UUID userId,
                   @Param("roleId") UUID roleId);

    /** 账号的有效权限点：只算 ACTIVE 角色授予的权限，停用角色立即失效。 */
    List<String> findPermissionCodes(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    // ---------- 门店数据范围 ----------

    int addHotelScope(@Param("tenantId") UUID tenantId,
                      @Param("userId") UUID userId,
                      @Param("hotelId") UUID hotelId);

    List<UUID> findHotelScopeIds(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

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
