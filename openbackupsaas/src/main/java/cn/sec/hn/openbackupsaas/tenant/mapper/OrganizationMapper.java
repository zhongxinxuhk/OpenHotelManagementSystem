package cn.sec.hn.openbackupsaas.tenant.mapper;

import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.HotelGroup;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 组织数据访问。SQL 全部写在 {@code resources/mapper/tenant/OrganizationMapper.xml}，
 * 便于按 ADR-011 的要求显式审查阅读而非依赖 ORM 生成。
 *
 * <p>注意：这里**不提供**绕过租户条件的查询方法。按 id 查询也必须带上 tenantId，
 * 防止应用层"先查到再判断"的越权路径（BL-07）。
 */
@Mapper
public interface OrganizationMapper {

    int insertTenant(Tenant tenant);

    Optional<Tenant> findTenantById(@Param("id") UUID id);

    Optional<Tenant> findTenantByCode(@Param("code") String code);

    List<Tenant> findAllTenants();

    int insertHotelGroup(HotelGroup group);

    /** 集团查询必须带租户条件。 */
    Optional<HotelGroup> findHotelGroup(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    List<HotelGroup> findHotelGroups(@Param("tenantId") UUID tenantId);

    int insertHotel(Hotel hotel);

    /** 门店查询必须带租户条件，跨租户 id 一律查不到。 */
    Optional<Hotel> findHotel(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    Optional<Hotel> findHotelByCode(@Param("tenantId") UUID tenantId, @Param("code") String code);

    List<Hotel> findHotels(@Param("tenantId") UUID tenantId);
}
