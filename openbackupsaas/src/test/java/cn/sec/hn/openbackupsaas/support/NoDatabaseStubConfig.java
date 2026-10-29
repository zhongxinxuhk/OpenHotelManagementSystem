package cn.sec.hn.openbackupsaas.support;

import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.HotelGroup;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.mapper.OrganizationMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 仅用于 {@code test} 档案：在不连接数据库时补齐 Mapper 依赖，让上下文装配检查可以运行。
 *
 * <p>为什么需要它：{@code test} 档案排除了 DataSource/Flyway/MyBatis 自动配置，
 * 因此 {@code @Mapper} 接口没有实现，服务类注入会失败。
 *
 * <p>这不是"用假实现测试业务"：任何方法被调用都会抛 {@link UnsupportedOperationException}，
 * 业务与数据库行为只能在 {@code integration} 档案（真实 PostgreSQL）下验证。
 */
@TestConfiguration(proxyBeanMethods = false)
public class NoDatabaseStubConfig {

    private static final String MESSAGE =
            "test 档案不提供数据访问实现：请用 integration 档案 + PMS_IT_DB=true 连真实 PostgreSQL 验证";

    @Bean
    OrganizationMapper organizationMapper() {
        return new OrganizationMapperStub();
    }

    static final class OrganizationMapperStub implements OrganizationMapper {

        @Override
        public int insertTenant(Tenant tenant) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<Tenant> findTenantById(UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<Tenant> findTenantByCode(String code) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<Tenant> findAllTenants() {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertHotelGroup(HotelGroup group) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<HotelGroup> findHotelGroup(UUID tenantId, UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<HotelGroup> findHotelGroups(UUID tenantId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertHotel(Hotel hotel) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<Hotel> findHotel(UUID tenantId, UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<Hotel> findHotelByCode(UUID tenantId, String code) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<Hotel> findHotels(UUID tenantId) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }
}
