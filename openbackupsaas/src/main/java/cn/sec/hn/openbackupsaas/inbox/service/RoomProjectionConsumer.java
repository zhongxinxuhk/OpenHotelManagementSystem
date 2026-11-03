package cn.sec.hn.openbackupsaas.inbox.service;

import cn.sec.hn.openbackupsaas.inbox.domain.InboxMessage;
import cn.sec.hn.openbackupsaas.inbox.mapper.InboxMapper;
import cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow;
import cn.sec.hn.openbackupsaas.inventory.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;

/**
 * 订单事件驱动的房态投影维护（S2-02 的第一个消费者）。
 *
 * <h2>为什么拿它当第一个消费者</h2>
 * {@code room_inventory_day} 是派生读模型（ADR-001），S1-12 只提供了手动重建。
 * 没有消费者时，这张表要靠人记得去点"重建"才会更新——这类表最后一定会烂掉。
 * 让它跟着订单事件自动重算，是这张表能长期可用的前提，也是"消费侧确实在改业务状态"的真实例子。
 *
 * <h2>为什么是整段重算而不是增量</h2>
 * 重算是幂等的：同样的权威记录算出同样的结果，重复投递不会把数据改坏。
 * 增量更新需要精确知道"这条事件改动了哪几格"，而一旦算错就是静默的数据漂移。
 * 代价是每次多算一个窗口——当前窗口上限 62 天且按订单跨度裁剪，可接受；
 * 真正的增量优化属 S2-05（派生修复与对账）。
 *
 * <h2>为什么不用线程上下文里的租户</h2>
 * 消费发生在 Worker 线程，没有请求上下文。租户来自消息体（发布时写入的可信字段），
 * 每一次查询都显式带上它，不依赖任何线程级状态——这正是"异步路径显式传递授权上下文"。
 */
@Component
public class RoomProjectionConsumer extends InboxConsumer {

    /** 订阅的订单事件类型。改名或新增事件类型时，这里必须同步，否则会被记为 IGNORED。 */
    private static final Set<String> EVENT_TYPES = Set.of(
            "ReservationCreated", "ReservationConfirmed", "ReservationCancelled",
            "ReservationNoShow", "ReservationExpired");

    private static final Logger log = LoggerFactory.getLogger(RoomProjectionConsumer.class);

    private final InboxMapper inboxMapper;
    private final InventoryService inventoryService;

    public RoomProjectionConsumer(InboxMapper inboxMapper, InventoryService inventoryService) {
        this.inboxMapper = inboxMapper;
        this.inventoryService = inventoryService;
    }

    @Override
    public String name() {
        return "room-inventory-projection";
    }

    @Override
    public Set<String> handledEventTypes() {
        return EVENT_TYPES;
    }

    @Override
    public void apply(InboxMessage message) {
        if (message.hotelId() == null) {
            // 门店维度的投影没有门店就算不出来。记一条 warn 而不是抛异常：
            // 抛异常会让这条消息无限重投，而它缺少的是永远补不回来的字段。
            log.warn("事件 {} 没有 hotelId，跳过投影重算", message.eventId());
            return;
        }

        InboxMapper.StaySpan span = inboxMapper.findStaySpan(message.tenantId(), message.hotelId(),
                message.aggregateId());
        if (span.isEmpty()) {
            log.info("订单 {} 没有订单行（可能已清理），事件 {} 不触发投影重算",
                    message.aggregateId(), message.eventId());
            return;
        }

        // 按窗口上限分块：单次重算超限会在 InventoryWindow 构造时抛异常，
        // 那会变成"一条超长订单永久卡住消费"，不如分块算完。
        LocalDate cursor = span.from();
        while (cursor.isBefore(span.to())) {
            LocalDate candidate = cursor.plusDays(InventoryWindow.MAX_WINDOW_DAYS);
            LocalDate end = candidate.isAfter(span.to()) ? span.to() : candidate;
            inventoryService.rebuild(new InventoryWindow(message.tenantId(), message.hotelId(), null, cursor, end));
            cursor = end;
        }
        log.debug("按事件 {} 重算了门店 {} 的投影窗口 [{} → {})",
                message.eventId(), message.hotelId(), span.from(), span.to());
    }
}
