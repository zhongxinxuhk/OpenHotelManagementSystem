package cn.sec.hn.openbackupsaas.support;

import cn.sec.hn.openbackupsaas.identity.domain.AppUser;
import cn.sec.hn.openbackupsaas.identity.domain.Role;
import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 集成测试用的身份夹具（S1-03）。
 *
 * <p>为什么单独抽出来：认证测试与接口测试都需要"一个租户 + 两间门店 + 一个账号 + 一个角色"，
 * 各写一份必然漂移。这里只做**最小可信数据**，所有断言仍走真实服务与真实数据库。
 *
 * <p>夹具不授予隐式权限：权限点与门店范围都由调用方显式指定，
 * 需要"没有权限"的用例就不传，避免夹具悄悄把权限给全。
 */
public final class IdentitySeed {

    /** 满足长度要求的口令；改密用例会另给新口令。 */
    public static final String RAW_PASSWORD = "Pms-Example-2026";

    private IdentitySeed() {
    }

    /**
     * 建一个租户、两间门店、一个账号。
     *
     * @param permissionCodes 授予该账号的权限点（可为空数组表示"无任何权限"）
     * @param grantFirstHotel 是否把第一家门店加入该账号的数据范围
     */
    public static Seed seed(OrganizationService organizations,
                            IdentityMapper identity,
                            PasswordEncoder encoder,
                            String suffix,
                            String[] permissionCodes,
                            boolean grantFirstHotel) {
        Tenant tenant = organizations.createTenant("t-" + suffix, "测试租户 " + suffix);
        Hotel hotel = organizations.createHotel(tenant.id(), null, "h" + suffix, "主店 " + suffix, "Asia/Shanghai");
        Hotel second = organizations.createHotel(tenant.id(), null, "s" + suffix, "次店 " + suffix, "Asia/Shanghai");

        String username = "u" + suffix;
        AppUser user = AppUser.create(UUID.randomUUID(), tenant.id(), username, "员工 " + suffix,
                encoder.encode(RAW_PASSWORD), OffsetDateTime.now());
        identity.insertUser(user);

        if (permissionCodes.length > 0) {
            Role role = Role.create(UUID.randomUUID(), tenant.id(), "ADMIN", "管理员", OffsetDateTime.now());
            identity.insertRole(role);
            for (String code : permissionCodes) {
                identity.grantPermission(tenant.id(), role.id(), code);
            }
            identity.assignRole(tenant.id(), user.id(), role.id());
        }
        if (grantFirstHotel) {
            identity.addHotelScope(tenant.id(), user.id(), hotel.id());
        }
        return new Seed(tenant, hotel, second, user, username);
    }

    /** 夹具结果；{@code secondHotel} 故意不加入数据范围，用于越权用例。 */
    public record Seed(Tenant tenant, Hotel hotel, Hotel secondHotel, AppUser user, String username) {

        public String tenantCode() {
            return tenant.code();
        }

        public UUID userId() {
            return user.id();
        }

        public UUID tenantId() {
            return tenant.id();
        }

        public UUID hotelId() {
            return hotel.id();
        }

        public UUID secondHotelId() {
            return secondHotel.id();
        }
    }
}
