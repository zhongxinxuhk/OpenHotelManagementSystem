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

    /**
     * 按 id 集合取门店，且必须同时带 tenantId。
     *
     * <p>用于"当前身份数据范围内的门店"这类查询：调用方把可信身份里的
     * {@code hotelIds} 传进来，SQL 在库层就完成租户收窄与范围收窄。
     * 空集合直接返回空列表，不退化成"返回全部门店"——那正是撤权变扩权的地方。
     */
    List<Hotel> findHotelsByIds(@Param("tenantId") UUID tenantId, @Param("ids") List<UUID> ids);
}
