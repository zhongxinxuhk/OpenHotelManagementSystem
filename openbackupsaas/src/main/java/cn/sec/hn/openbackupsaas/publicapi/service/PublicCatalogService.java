package cn.sec.hn.openbackupsaas.publicapi.service;

import cn.sec.hn.openbackupsaas.inventory.domain.BookableRoomType;
import cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow;
import cn.sec.hn.openbackupsaas.inventory.service.InventoryService;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.OrgStatus;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.domain.TenantStatus;
import cn.sec.hn.openbackupsaas.tenant.mapper.OrganizationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 匿名公开目录（J-02，S4-01 前的第一块）。
 *
 * <h2>这是系统里唯一"没有身份"的读取路径</h2>
 * 它存在的理由很窄：住客在注册/登录之前必须能看到有哪些房型、还有没有房。
 * 除此之外的一切都要求身份——所以本类**只做只读、只做目录**，
 * 不提供任何"按订单号查详情"之类的入口（那是 J-03，且必须绑定住客身份）。
 *
 * <h2>三条硬约束</h2>
 * <ol>
 *   <li><b>定位用编码，不用 UUID。</b>客户端传 {@code (tenantCode, hotelCode)}。
 *       门店编码只在租户内唯一（{@code uq_hotel_code UNIQUE (tenant_id, code)}），
 *       而 UUID 外泄等于把内部标识送出去——公开面一旦用了它，全网的爬虫都能按序号猜。</li>
 *   <li><b>不存在与不可见一律同一个 404。</b>租户停用、门店停用、编码写错，
 *       对外都是"门店不存在或未开放预订"。区分这三者，等于提供一个
 *       "这个门店是否存在过"的探测接口。</li>
 *   <li><b>只返回公开字段。</b>不含内部 id、总房数、已售数、封房数；
 *       可售只给布尔值。"仅剩 3 间"这种话术是营销决策，不是后端该默认暴露的事实。</li>
 * </ol>
 *
 * <h2>为什么默认关闭</h2>
 * 见 {@code pms.public-api.enabled}：在 J-05（限流与防刷）落地之前，这个接口不对外。
 * 一个可被无限刷的匿名查询接口，就是一台免费的库存探测机——比别写它更糟。
 */
@Service
public class PublicCatalogService {

    private static final Logger log = LoggerFactory.getLogger(PublicCatalogService.class);

    /** 对外统一文案：不区分"不存在"与"已停用"。 */
    private static final String NOT_FOUND = "门店不存在或未开放预订";

    private final OrganizationMapper organizationMapper;
    private final RoomService roomService;
    private final InventoryService inventoryService;

    public PublicCatalogService(OrganizationMapper organizationMapper,
                                RoomService roomService,
                                InventoryService inventoryService) {
        this.organizationMapper = organizationMapper;
        this.roomService = roomService;
        this.inventoryService = inventoryService;
    }

    /**
     * 某门店在某段时间内的可售房型目录。
     *
     * @param tenantCode 租户编码（客户端可见的对外标识）
     * @param hotelCode  门店编码（租户内唯一）
     * @throws NotFoundException      租户或门店不可见（不存在 / 停用）
     * @throws IllegalArgumentException 日期区间不合法或超过 62 天
     */
    @Transactional(readOnly = true)
    public Catalog catalog(String tenantCode, String hotelCode, LocalDate from, LocalDate to) {
        String normalizedTenant = normalize(tenantCode);
        String normalizedHotel = normalize(hotelCode);

        Tenant tenant = organizationMapper.findTenantByCode(normalizedTenant)
                // 只有 ACTIVE 对外可见：SUSPENDED（停止写入）与 CLOSED（关停只读）
                // 都不该继续对外承接新预订。
                .filter(candidate -> candidate.status() == TenantStatus.ACTIVE)
                .orElseThrow(() -> notFound(normalizedTenant, normalizedHotel));

        Hotel hotel = organizationMapper.findHotelByCode(tenant.id(), normalizedHotel)
                .filter(candidate -> candidate.status() == OrgStatus.ACTIVE)
                .orElseThrow(() -> notFound(normalizedTenant, normalizedHotel));

        // 窗口上限（62 天）由 InventoryWindow 的构造器强制，超限即 400。
        InventoryWindow window = new InventoryWindow(tenant.id(), hotel.id(), null, from, to);

        Map<UUID, Integer> bookableByRoomType = inventoryService.bookableRooms(window).stream()
                .collect(Collectors.toMap(BookableRoomType::roomTypeId,
                        item -> item.bookableRooms() == null ? 0 : item.bookableRooms(),
                        (left, right) -> left));

        List<RoomType> visibleRoomTypes = roomService.listRoomTypes(tenant.id(), hotel.id()).stream()
                // 停用房型不对外：它在商家端是"暂不售卖"的意思。
                .filter(roomType -> roomType.status() == OrgStatus.ACTIVE)
                .toList();

        List<CatalogRoomType> items = visibleRoomTypes.stream()
                .map(roomType -> new CatalogRoomType(
                        roomType,
                        bookableByRoomType.getOrDefault(roomType.id(), 0) > 0))
                .toList();

        log.debug("public catalog tenantCode={} hotelCode={} from={} to={} roomTypes={}",
                normalizedTenant, normalizedHotel, from, to, items.size());

        return new Catalog(tenant.code(), hotel, from, to, window.nights(), items);
    }

    private static NotFoundException notFound(String tenantCode, String hotelCode) {
        log.info("public catalog not visible tenantCode={} hotelCode={}", tenantCode, hotelCode);
        return new NotFoundException(NOT_FOUND);
    }

    /** 编码统一小写：库里的小写约束是 {@code ^[a-z0-9][a-z0-9-]{1,31}$}。 */
    private static String normalize(String code) {
        if (code == null || code.isBlank()) {
            throw new NotFoundException(NOT_FOUND);
        }
        return code.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 目录结果（内部模型）。
     *
     * <p>含 {@link RoomType} 实体是因为控制器要做字段裁剪；**不要**把这个记录直接序列化出去，
     * 它带着内部 UUID。对外结构见 {@code PublicCatalogController} 里的 View。
     */
    public record Catalog(String tenantCode,
                          Hotel hotel,
                          LocalDate from,
                          LocalDate to,
                          int nights,
                          List<CatalogRoomType> roomTypes) {
    }

    /** 房型 + 该区间是否可售。可售的判定口径来自库存服务，不在公开面另算一套。 */
    public record CatalogRoomType(RoomType roomType, boolean bookable) {
    }
}
