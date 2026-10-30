package cn.sec.hn.openbackupsaas.inventory.web;

import cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow;
import cn.sec.hn.openbackupsaas.inventory.domain.ReconcileReport;
import cn.sec.hn.openbackupsaas.inventory.service.InventoryService;
import cn.sec.hn.openbackupsaas.shared.security.HotelAccess;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 房态与可售接口（S1-12）。
 *
 * <p>三个入口的权限刻意分开：
 * <ul>
 *   <li>{@code /availability}、{@code /inventory/reconcile} 只要 {@code inventory:read}——
 *       前者是前台与渠道的日常查询，后者是只读诊断；</li>
 *   <li>{@code /inventory/rebuild} 要 {@code inventory:write}——它整段改写派生数据，
 *       属运维动作，不能跟着"能看房态"一起给出去。</li>
 * </ul>
 *
 * <p>所有入口都先经 {@link HotelAccess}：门店不属于本租户 → 404，不在数据范围 → 403。
 * 查询窗口由 {@link InventoryWindow} 的构造器限死（最多 62 天），超限直接 400，
 * 不让"查一年"这种参数进到数据库。
 */
@RestController
@RequestMapping("/api/v1/hotels/{hotelId}")
public class InventoryController {

    private final InventoryService inventoryService;
    private final HotelAccess hotelAccess;

    public InventoryController(InventoryService inventoryService, HotelAccess hotelAccess) {
        this.inventoryService = inventoryService;
        this.hotelAccess = hotelAccess;
    }

    /** 每日余量 + 整段可售，一次返回。 */
    @GetMapping("/availability")
    @PreAuthorize("hasAuthority('inventory:read')")
    public AvailabilityView availability(
            @PathVariable UUID hotelId,
            @RequestParam(required = false) UUID roomTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        InventoryWindow window = new InventoryWindow(tenantId, hotelId, roomTypeId, from, to);
        return new AvailabilityView(from, to, window.nights(),
                inventoryService.dailyAvailability(window),
                inventoryService.bookableRooms(window));
    }

    /**
     * 重建窗口内的房态投影（运维动作）。
     *
     * <p>重建前后都是同一份权威记录算出来的结果，所以重复调用是幂等的：
     * 第二次会先删掉刚写的行再写回来，行数一致。
     */
    @PostMapping("/inventory/rebuild")
    @PreAuthorize("hasAuthority('inventory:write')")
    public InventoryService.RebuildResult rebuild(
            @PathVariable UUID hotelId,
            @RequestParam(required = false) UUID roomTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        return inventoryService.rebuild(new InventoryWindow(tenantId, hotelId, roomTypeId, from, to));
    }

    /**
     * 三方对账报告：权威重算 vs 投影 vs 流水。
     *
     * <p>{@code clean = true} 才表示这段窗口的投影与记账都可信；否则按 {@code differences}
     * 里的 {@code kind} 定位是"投影没建"、"标记过期"、"多余行"还是"流水对不上"。
     */
    @GetMapping("/inventory/reconcile")
    @PreAuthorize("hasAuthority('inventory:read')")
    public ReconcileReport reconcile(
            @PathVariable UUID hotelId,
            @RequestParam(required = false) UUID roomTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID tenantId = hotelAccess.requireAccess(hotelId);
        return inventoryService.reconcile(new InventoryWindow(tenantId, hotelId, roomTypeId, from, to));
    }
}
