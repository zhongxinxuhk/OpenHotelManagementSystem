package cn.sec.hn.openbackupsaas.journal.service;

import cn.sec.hn.openbackupsaas.journal.domain.ActorType;
import cn.sec.hn.openbackupsaas.journal.domain.AuditActions;
import cn.sec.hn.openbackupsaas.journal.domain.AuditLog;
import cn.sec.hn.openbackupsaas.journal.domain.EffectKind;
import cn.sec.hn.openbackupsaas.journal.domain.InventoryLedger;
import cn.sec.hn.openbackupsaas.journal.domain.OutboxEvent;
import cn.sec.hn.openbackupsaas.journal.mapper.JournalMapper;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.shared.security.TenantContext;
import cn.sec.hn.openbackupsaas.shared.security.TenantContextHolder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 交易写入的公共组件（S1-06）：库存流水、关键审计、Outbox 事件。
 *
 * <p><b>原子性由调用方的事务提供</b>，不由本类提供：这些方法只是"在同一个事务里追加事实"。
 * 调用方（如 {@code OccupancyService}）必须自身处于事务中，否则
 * "占用写了、流水没写"这类半截状态没人拦得住。
 *
 * <p>三条写入纪律：
 * <ol>
 *   <li>流水按"房间 + 住宿日"一行，不用数量表达多晚；重复入账由唯一约束拒绝。</li>
 *   <li>审计的动作名必须来自 {@link AuditActions}，不写字符串字面量。</li>
 *   <li>事件载荷只写不含敏感信息的字段（id、区间、状态），不写口令、令牌、证件。</li>
 * </ol>
 */
@Service
public class JournalService {

    private static final String AGGREGATE_ROOM_OCCUPANCY = "room_occupancy";
    private static final int EVENT_SCHEMA_VERSION = 1;

    private final JournalMapper journalMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JournalService(JournalMapper journalMapper, ObjectMapper objectMapper, Clock clock) {
        this.journalMapper = journalMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 记录一次占用效果：占用写 -1，释放写 +1（对冲行，不删历史）。
     *
     * @return 本次写入的流水行（一晚一行）
     */
    public List<InventoryLedger> recordOccupancyEffect(UUID tenantId,
                                                       UUID hotelId,
                                                       UUID roomId,
                                                       StayRange stayRange,
                                                       EffectKind effectKind,
                                                       boolean occupy,
                                                       String reasonCode) {
        return recordOccupancyEffect(UUID.randomUUID(), tenantId, hotelId, roomId, stayRange, effectKind,
                occupy, reasonCode);
    }

    /**
     * @param operationId 一次命令的操作标识。同一操作重复写同一晚会被唯一键拒绝；
     *                    不同操作（占用 → 释放 → 再占用）相互独立，业务循环合法。
     */
    public List<InventoryLedger> recordOccupancyEffect(UUID operationId,
                                                       UUID tenantId,
                                                       UUID hotelId,
                                                       UUID roomId,
                                                       StayRange stayRange,
                                                       EffectKind effectKind,
                                                       boolean occupy,
                                                       String reasonCode) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<InventoryLedger> rows = new ArrayList<>();
        for (LocalDate date : stayRange.dates()) {
            InventoryLedger row = occupy
                    ? InventoryLedger.occupy(operationId, tenantId, hotelId, roomId, date, effectKind,
                    reasonCode, stayRange.checkIn(), now)
                    : InventoryLedger.release(operationId, tenantId, hotelId, roomId, date, effectKind,
                    reasonCode, stayRange.checkIn(), now);
            journalMapper.insertLedger(row);
            rows.add(row);
        }
        return rows;
    }

    /** 写一条关键审计。动作名不在目录里时直接拒绝，避免目录与库里的数据慢慢漂移。 */
    public AuditLog recordAudit(UUID tenantId,
                                UUID hotelId,
                                String action,
                                String targetType,
                                UUID targetId,
                                Long beforeVersion,
                                Long afterVersion,
                                Map<String, Object> changeSummary) {
        if (!AuditActions.isKnown(action)) {
            throw new IllegalArgumentException("审计动作未登记在 AuditActions 目录：" + action);
        }
        Actor actor = currentActor();
        AuditLog audit = AuditLog.of(tenantId, hotelId, action, actor.type(), actor.id(), targetType,
                targetId, beforeVersion, afterVersion, toJson(changeSummary), OffsetDateTime.now(clock));
        journalMapper.insertAudit(audit);
        return audit;
    }

    /**
     * 写一条待发布事件。序号在同一"聚合 + 版本"内递增，由调用方给出。
     *
     * <p>为什么序号不由这里自增：自增需要读当前最大值并加锁，等于在热路径上加一次竞争；
     * 而同一事务内调用方本来就知道自己写了几条事件，让它数更便宜也更可测。
     */
    public OutboxEvent recordEvent(UUID tenantId,
                                   UUID hotelId,
                                   String aggregateType,
                                   UUID aggregateId,
                                   long aggregateVersion,
                                   int eventOrdinal,
                                   String eventType,
                                   Map<String, Object> payload) {
        OutboxEvent event = OutboxEvent.pending(tenantId, hotelId, aggregateType, aggregateId,
                aggregateVersion, eventOrdinal, eventType, EVENT_SCHEMA_VERSION, toJson(payload),
                OffsetDateTime.now(clock));
        journalMapper.insertEvent(event);
        return event;
    }

    /** 占用相关事件的聚合名，供调用方与订阅方统一引用。 */
    public static String occupancyAggregate() {
        return AGGREGATE_ROOM_OCCUPANCY;
    }

    /**
     * 当前动作主体：有可信身份上下文时记为 USER，否则记为 SYSTEM。
     *
     * <p>后台任务应当先用 {@code TenantContextHolder.runAs} 显式绑定自己的上下文（B-07），
     * 那时它会带自己的身份；没有绑定就是系统路径，如实记为 SYSTEM，不假装是某个用户。
     */
    public static Actor currentActor() {
        Optional<TenantContext> context = TenantContextHolder.current();
        return context
                .map(value -> new Actor(ActorType.USER, value.userId()))
                .orElseGet(() -> new Actor(ActorType.SYSTEM, null));
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            // 载荷序列化失败必须让整个事务失败：宁可不写，也不写一条载荷损坏的事件
            throw new IllegalStateException("审计/事件载荷无法序列化", ex);
        }
    }

    /** 动作主体。 */
    public record Actor(ActorType type, UUID id) {
    }
}
