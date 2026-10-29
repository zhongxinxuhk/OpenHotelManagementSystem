package cn.sec.hn.openbackupsaas.shared.security;

import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import cn.sec.hn.openbackupsaas.tenant.mapper.OrganizationMapper;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 门店级入口校验（S1-04 引入，后续 S1-05/S1-11 复用）。
 *
 * <p>顺序是固定的，不能调换：
 * <ol>
 *   <li>租户只从 {@link Authz#tenantId()} 取，绝不读路径/请求体里的 tenant 值；</li>
 *   <li>先判"该门店是否属于当前租户" → 不属于则 **404**（不能让 403 泄漏别的租户有这家门店）；</li>
 *   <li>再判"该门店是否在当前身份的门店范围内" → 不在则 **403**。</li>
 * </ol>
 *
 * <p>把这一步抽出来而不是让每个控制器自己拼，是因为顺序一旦被人"顺手优化"，
 * 就会变成对象存在性泄露，而这种缺陷测试很难覆盖到每个接口。
 */
@Component
public class HotelAccess {

    private final Authz authz;
    private final OrganizationMapper organizationMapper;

    public HotelAccess(Authz authz, OrganizationMapper organizationMapper) {
        this.authz = authz;
        this.organizationMapper = organizationMapper;
    }

    /** 校验通过后返回当前租户 id，供服务层作为每条 SQL 的条件使用。 */
    public UUID requireAccess(UUID hotelId) {
        UUID tenantId = authz.tenantId();
        if (hotelId == null) {
            throw new NotFoundException("门店不存在：null");
        }
        organizationMapper.findHotel(tenantId, hotelId)
                .orElseThrow(() -> new NotFoundException("门店不存在：" + hotelId));
        authz.requireHotel(hotelId);
        return tenantId;
    }
}
