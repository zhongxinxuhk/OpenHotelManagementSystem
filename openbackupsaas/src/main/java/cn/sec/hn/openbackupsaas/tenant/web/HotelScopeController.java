package cn.sec.hn.openbackupsaas.tenant.web;

import cn.sec.hn.openbackupsaas.shared.security.Authz;
import io.swagger.v3.oas.annotations.Operation;
import cn.sec.hn.openbackupsaas.shared.security.TenantContext;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.mapper.OrganizationMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 当前身份可见门店清单（S3-01 前端对接）。
 *
 * <h2>为什么需要这个接口</h2>
 * 几乎所有业务接口都是 {@code /api/v1/hotels/{hotelId}/...} 形状——房型、房间、订单、
 * 可售、对账都以门店为路径参数。调用方必须先知道**有哪些门店可选**、以及**哪一间是自己的**，
 * 否则前端只能硬编码门店 id，或者退化成"让用户手填 UUID"。
 *
 * <h2>返回的是范围，不是租户全量</h2>
 * 返回集合严格等于当前身份在 {@code user_hotel_scope} 里的门店：
 * <ul>
 *   <li>范围为空 → 返回空数组。前端据此显示"当前账号未分配门店"，而不是把下拉框清空后
 *       让用户以为是加载失败；</li>
 *   <li>范围非空 → 只返回范围内的门店，且 SQL 同时带 {@code tenant_id} 条件，
 *       跨租户门店即使被写进 scope 表也查不出来（复合外键本就不允许，但这里不依赖那一层）。</li>
 * </ul>
 *
 * <h2>权限</h2>
 * 要求 {@code org:read}（读取组织/门店档案）。这是"能看到自己有哪些门店"的最小能力，
 * 不涉及任何业务数据，因此不与 {@code reservation:read} 之类的业务权限混在一起。
 */
@RestController
@RequestMapping("/api/v1/hotels")
public class HotelScopeController {

    private final OrganizationMapper organizationMapper;
    private final Authz authz;

    public HotelScopeController(OrganizationMapper organizationMapper, Authz authz) {
        this.organizationMapper = organizationMapper;
        this.authz = authz;
    }

    /**
     * 当前身份可操作的门店清单。
     *
     * <p>租户与范围都来自可信身份上下文，不接受任何请求参数——
     * 客户端没有资格指定"我要看谁的门店"。
     */
    @GetMapping
    @PreAuthorize("hasAuthority('org:read')")
    @Operation(operationId = "listHotels")
    public List<HotelView> list() {
        TenantContext context = authz.context();
        UUID tenantId = context.tenantId();
        List<UUID> scoped = List.copyOf(context.hotelIds());
        return organizationMapper.findHotelsByIds(tenantId, scoped).stream()
                .map(HotelView::of)
                .toList();
    }
}
