package cn.sec.hn.openbackupsaas.publicapi.web;

import cn.sec.hn.openbackupsaas.publicapi.service.PublicCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 匿名公开目录接口（J-02）。
 *
 * <h2>命名空间</h2>
 * {@code /api/v1/public/**} 是全系统**唯一**的匿名前缀（ADR-017 §2.6）。
 * 新增匿名接口只能落在这里，这样才能用一条规则做限流、WAF、监控与"是否对外"的开关。
 * 混进 {@code /api/v1/hotels/**} 会让"哪些路径不需要身份"变得需要逐个记忆。
 *
 * <h2>默认关闭</h2>
 * 由 {@code pms.public-api.enabled} 控制，默认 {@code false}：
 * 限流（J-05）落地前不对外暴露。关闭时本控制器不会注册，
 * 路径在安全链里虽然是公开的，但没有任何映射 → 404，与"这个接口不存在"一致。
 * 这比"能访问但返回 503"更干净：对扫描者来说它压根不存在。
 *
 * <h2>没有身份，就没有@PreAuthorize</h2>
 * 这里刻意不加任何权限注解——不是漏了。加上去反而会让人误以为它受租户授权保护。
 * 它防泄漏靠的是"只返回公开字段 + 不可见一律 404"，见
 * {@link PublicCatalogService}。
 */
@RestController
@RequestMapping("/api/v1/public")
@ConditionalOnProperty(name = "pms.public-api.enabled", havingValue = "true")
public class PublicCatalogController {

    private final PublicCatalogService catalogService;

    public PublicCatalogController(PublicCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * 门店的房型目录与可售状态。
     *
     * @param tenantCode 租户编码（对外标识）
     * @param hotelCode  门店编码（租户内唯一，所以必须与租户编码一起给）
     * @param from       入住日期（含）
     * @param to         离店日期（不含）；区间最多 62 天
     */
    @GetMapping("/{tenantCode}/hotels/{hotelCode}/room-types")
    @Operation(operationId = "publicListRoomTypes")
    public PublicCatalogView roomTypes(
            @PathVariable String tenantCode,
            @PathVariable String hotelCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return PublicCatalogView.of(catalogService.catalog(tenantCode, hotelCode, from, to));
    }

    // ==================== 响应模型 ====================

    /**
     * 对外目录。
     *
     * <p>字段清单就是"允许外泄的全部"：门店名、时区、房型名与容量、是否可售。
     * 加字段前先问一句"这个信息被竞争对手拿到会怎样"——
     * 总房数、已售数、封房数都不在这个清单里。
     */
    public record PublicCatalogView(
            String tenantCode,
            PublicHotelView hotel,
            LocalDate from,
            LocalDate to,
            int nights,
            List<PublicRoomTypeView> roomTypes
    ) {
        public static PublicCatalogView of(PublicCatalogService.Catalog catalog) {
            return new PublicCatalogView(
                    catalog.tenantCode(),
                    new PublicHotelView(catalog.hotel().code(), catalog.hotel().name(),
                            catalog.hotel().timezone()),
                    catalog.from(),
                    catalog.to(),
                    catalog.nights(),
                    catalog.roomTypes().stream().map(PublicRoomTypeView::of).toList());
        }
    }

    /** 门店的公开信息：只有编码、名称、时区（时区用于客户端算"今晚"是哪一天）。 */
    public record PublicHotelView(String code, String name, String timezone) {
    }

    /** 房型的公开信息 + 该区间是否可售。**不含**剩余间数与内部 id。 */
    public record PublicRoomTypeView(
            String code,
            String name,
            Integer capacityAdults,
            Integer capacityChildren,
            String bedType,
            boolean bookable
    ) {
        public static PublicRoomTypeView of(PublicCatalogService.CatalogRoomType source) {
            return new PublicRoomTypeView(
                    source.roomType().code(),
                    source.roomType().name(),
                    source.roomType().capacityAdults(),
                    source.roomType().capacityChildren(),
                    source.roomType().bedType(),
                    source.bookable());
        }
    }
}
