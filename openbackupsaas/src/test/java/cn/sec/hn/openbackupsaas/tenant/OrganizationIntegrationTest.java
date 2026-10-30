package cn.sec.hn.openbackupsaas.tenant;

import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.HotelGroup;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.mapper.OrganizationMapper;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1-02 组织基线集成测试：**必须连真实 PostgreSQL**。
 *
 * <p>运行方式：
 * <pre>
 *   ./scripts/dev-db.sh start
 *   PMS_IT_DB=true ./mvnw -B test -Dtest=OrganizationIntegrationTest
 * </pre>
 * 未设置 {@code PMS_IT_DB=true} 时整类跳过，确保无数据库环境仍可跑单元测试
 * （这也是"未验证项不得宣称通过"的落地方式：跳过而不是伪造通过）。
 *
 * <p>每条用例独立：由 {@link PostgresIntegrationTest} 的基类钩子在每个方法前清空全库
 * （{@code DatabaseCleaner} 按外键拓扑序删并断言全空），**不使用事务回滚**——
 * 外键与权限违反必须在语句级真实失败，回滚会让"约束到底有没有生效"变得不可观测。
 */
class OrganizationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private OrganizationMapper organizationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayBaselineIsAppliedOnEmptyDatabase() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertNotNull(applied);
        assertTrue(applied >= 1, "空库应至少应用 V1 迁移");

        assertEquals("hotel", jdbcTemplate.queryForObject("SELECT to_regclass('public.hotel')::text", String.class));
        assertEquals("hotel_group",
                jdbcTemplate.queryForObject("SELECT to_regclass('public.hotel_group')::text", String.class));
        assertEquals("tenant", jdbcTemplate.queryForObject("SELECT to_regclass('public.tenant')::text", String.class));
    }

    @Test
    void createsTenantGroupAndHotelWithNormalizedCodes() {
        Tenant tenant = organizationService.createTenant("ACME-Hotel", "  Acme 酒店集团 ");
        assertEquals("acme-hotel", tenant.code(), "编码应统一小写并去除首尾空格");
        assertEquals("Acme 酒店集团", tenant.name());
        assertTrue(tenant.status().allowsWrite());

        HotelGroup group = organizationService.createHotelGroup(tenant.id(), "north", "华北区域");
        Hotel hotel = organizationService.createHotel(
                tenant.id(), group.id(), "h-001", "北京示例店", "Asia/Shanghai");

        assertEquals(tenant.id(), hotel.tenantId());
        assertEquals(group.id(), hotel.groupId());
        assertEquals("Asia/Shanghai", hotel.timezone());
        assertEquals(1, organizationService.listHotels(tenant.id()).size());
    }

    @Test
    void duplicateTenantCodeRejectedByService() {
        organizationService.createTenant("dup-tenant", "第一家");
        assertThrows(ConflictException.class, () -> organizationService.createTenant("dup-tenant", "第二家"));
    }

    @Test
    void duplicateTenantCodeRejectedByDatabaseConstraint() {
        organizationService.createTenant("dup-db", "第一家");
        Tenant duplicate = Tenant.create(UUID.randomUUID(), "dup-db", "绕过服务的第二家", OffsetDateTime.now());

        assertThrows(DataIntegrityViolationException.class, () -> organizationMapper.insertTenant(duplicate),
                "uq_tenant_code 必须挡住绕过服务层的重复编码");
    }

    @Test
    void crossTenantGroupRejectedByService() {
        Tenant tenantA = organizationService.createTenant("tenant-a", "甲");
        Tenant tenantB = organizationService.createTenant("tenant-b", "乙");
        HotelGroup groupA = organizationService.createHotelGroup(tenantA.id(), "ga", "甲集团");

        assertThrows(IllegalArgumentException.class,
                () -> organizationService.createHotel(tenantB.id(), groupA.id(), "hb", "乙店", "Asia/Shanghai"),
                "用乙的租户配甲的集团必须被拒绝");
    }

    @Test
    void crossTenantGroupRejectedByDatabaseConstraint() {
        Tenant tenantA = organizationService.createTenant("tenant-a", "甲");
        Tenant tenantB = organizationService.createTenant("tenant-b", "乙");
        HotelGroup groupA = organizationService.createHotelGroup(tenantA.id(), "ga", "甲集团");

        Hotel forged = Hotel.create(UUID.randomUUID(), tenantB.id(), groupA.id(), "hb", "乙店",
                "Asia/Shanghai", OffsetDateTime.now());
        assertThrows(DataIntegrityViolationException.class, () -> organizationMapper.insertHotel(forged),
                "fk_hotel_group_same_tenant 必须在数据库层拒绝跨租户挂接");
    }

    @Test
    void sameHotelCodeAllowedInDifferentTenants() {
        Tenant tenantA = organizationService.createTenant("tenant-a", "甲");
        Tenant tenantB = organizationService.createTenant("tenant-b", "乙");

        organizationService.createHotel(tenantA.id(), null, "h-100", "甲店", "Asia/Shanghai");
        organizationService.createHotel(tenantB.id(), null, "h-100", "乙店", "Australia/Sydney");

        assertEquals(1, organizationService.listHotels(tenantA.id()).size());
        assertEquals(1, organizationService.listHotels(tenantB.id()).size());
    }

    @Test
    void suspendedTenantRejectsNewWrites() {
        Tenant tenant = organizationService.createTenant("frozen", "停用租户");
        jdbcTemplate.update("UPDATE tenant SET status = 'SUSPENDED' WHERE id = ?", tenant.id());

        assertThrows(ConflictException.class,
                () -> organizationService.createHotel(tenant.id(), null, "h-200", "不应创建", "Asia/Shanghai"),
                "停用租户不得新增门店");
    }

    @Test
    void cannotDeleteTenantStillReferencedByHotel() {
        Tenant tenant = organizationService.createTenant("keeper", "有门店的租户");
        organizationService.createHotel(tenant.id(), null, "h-300", "门店", "Asia/Shanghai");

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("DELETE FROM tenant WHERE id = ?", tenant.id()),
                "有业务引用的组织记录不能被删除");
    }

    @Test
    void appAccountCannotRunDdl() {
        // 分权验证：应用账号只有 DML 权限，建表必须失败
        assertThrows(DataAccessException.class,
                () -> jdbcTemplate.execute("CREATE TABLE should_not_exist (id int)"),
                "应用账号不得具备 DDL 权限");
    }

    @Test
    void crossTenantReadReturnsEmpty() {
        Tenant tenantA = organizationService.createTenant("tenant-a", "甲");
        Tenant tenantB = organizationService.createTenant("tenant-b", "乙");
        Hotel hotelA = organizationService.createHotel(tenantA.id(), null, "h-400", "甲店", "Asia/Shanghai");

        assertTrue(organizationMapper.findHotel(tenantB.id(), hotelA.id()).isEmpty(),
                "跨租户 id 查询必须查不到，而不是查到后再判断");
        assertThrows(IllegalArgumentException.class, () -> organizationService.requireHotel(tenantB.id(), hotelA.id()));
    }

    @Test
    void invalidFormatRejectedBeforeHittingDatabase() {
        Tenant tenant = organizationService.createTenant("valid-tenant", "合法租户");

        assertThrows(IllegalArgumentException.class, () -> organizationService.createTenant("非法 编码", "x"));
        assertThrows(IllegalArgumentException.class, () -> organizationService.createTenant("ok-tenant", "  "));
        assertThrows(IllegalArgumentException.class,
                () -> organizationService.createHotel(tenant.id(), null, "h-500", "店", "Mars/Olympus"));
    }
}
