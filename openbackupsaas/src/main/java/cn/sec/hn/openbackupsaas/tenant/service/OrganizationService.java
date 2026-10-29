package cn.sec.hn.openbackupsaas.tenant.service;

import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.HotelGroup;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.mapper.OrganizationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 组织写入与查询（S1-02 最小切片）。
 *
 * <p>职责边界：
 * <ul>
 *   <li>只负责组织自身的建立与读取，不做登录、角色与门店数据范围（S1-03）；</li>
 *   <li>状态前置校验在这里做（停用租户/集团不接受新门店），数据库约束仍是最后防线；</li>
 *   <li>不在此处编造"先这样跑"的业务规则：时区只校验 IANA 合法性，营业日与未分房策略等 S0-05 定案。</li>
 * </ul>
 */
@Service
public class OrganizationService {

    /** 与迁移 V1 的 ck_*_code_fmt 保持一致；两处不一致时以数据库为准并同步修正本常量。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,31}$");

    private final OrganizationMapper organizationMapper;
    private final Clock clock;

    public OrganizationService(OrganizationMapper organizationMapper, Clock clock) {
        this.organizationMapper = organizationMapper;
        this.clock = clock;
    }

    @Transactional
    public Tenant createTenant(String code, String name) {
        String normalizedCode = normalizeCode(code);
        String normalizedName = requireText(name, "租户名称");
        if (organizationMapper.findTenantByCode(normalizedCode).isPresent()) {
            throw new ConflictException("租户编码已存在：" + normalizedCode);
        }
        Tenant tenant = Tenant.create(UUID.randomUUID(), normalizedCode, normalizedName, now());
        organizationMapper.insertTenant(tenant);
        return tenant;
    }

    @Transactional
    public HotelGroup createHotelGroup(UUID tenantId, String code, String name) {
        requireWritableTenant(tenantId);
        String normalizedCode = normalizeCode(code);
        String normalizedName = requireText(name, "集团名称");
        // 编码唯一性由 uq_hotel_group_code 保证，违反时由统一异常处理转 409；
        // 这里不做"先查后插"，避免并发下仍然冲突却多一次往返。
        HotelGroup group = HotelGroup.create(UUID.randomUUID(), tenantId, normalizedCode, normalizedName, now());
        organizationMapper.insertHotelGroup(group);
        return group;
    }

    @Transactional
    public Hotel createHotel(UUID tenantId, UUID groupId, String code, String name, String timezone) {
        requireWritableTenant(tenantId);
        String normalizedCode = normalizeCode(code);
        String normalizedName = requireText(name, "门店名称");
        String zone = requireZoneId(timezone);
        if (groupId != null) {
            HotelGroup group = organizationMapper.findHotelGroup(tenantId, groupId)
                    .orElseThrow(() -> new IllegalArgumentException("集团不存在或不属于该租户：" + groupId));
            if (!group.status().allowsWrite()) {
                throw new ConflictException("集团已停用，不能新增门店：" + group.code());
            }
        }
        Hotel hotel = Hotel.create(UUID.randomUUID(), tenantId, groupId, normalizedCode, normalizedName, zone, now());
        organizationMapper.insertHotel(hotel);
        return hotel;
    }

    @Transactional(readOnly = true)
    public Tenant requireTenant(UUID tenantId) {
        return organizationMapper.findTenantById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("租户不存在：" + tenantId));
    }

    @Transactional(readOnly = true)
    public List<HotelGroup> listHotelGroups(UUID tenantId) {
        requireTenant(tenantId);
        return organizationMapper.findHotelGroups(tenantId);
    }

    @Transactional(readOnly = true)
    public List<Hotel> listHotels(UUID tenantId) {
        requireTenant(tenantId);
        return organizationMapper.findHotels(tenantId);
    }

    /** 跨租户 id 查不到：返回空而不是"查到再判断"，避免越权路径（BL-07）。 */
    @Transactional(readOnly = true)
    public Hotel requireHotel(UUID tenantId, UUID hotelId) {
        return organizationMapper.findHotel(tenantId, hotelId)
                .orElseThrow(() -> new IllegalArgumentException("门店不存在或不属于该租户：" + hotelId));
    }

    private Tenant requireWritableTenant(UUID tenantId) {
        Tenant tenant = requireTenant(tenantId);
        if (!tenant.status().allowsWrite()) {
            throw new ConflictException("租户当前状态不允许写入：" + tenant.status());
        }
        return tenant;
    }

    private static String normalizeCode(String code) {
        String trimmed = requireText(code, "编码").toLowerCase(java.util.Locale.ROOT);
        if (!CODE_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "编码格式不合法（小写字母或数字开头，2–32 位，可含连字符）：" + code);
        }
        return trimmed;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }

    private static String requireZoneId(String timezone) {
        String trimmed = requireText(timezone, "门店时区");
        try {
            return ZoneId.of(trimmed).getId();
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("时区不是合法的 IANA 名称：" + timezone);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
