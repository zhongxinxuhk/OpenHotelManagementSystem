package cn.sec.hn.openbackupsaas.journal.mapper;

import cn.sec.hn.openbackupsaas.journal.domain.AuditLog;
import cn.sec.hn.openbackupsaas.journal.domain.EffectKind;
import cn.sec.hn.openbackupsaas.journal.domain.InventoryLedger;
import cn.sec.hn.openbackupsaas.journal.domain.OutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 流水、审计与事件的数据访问（S1-06）。SQL 见 {@code resources/mapper/journal/JournalMapper.xml}。
 *
 * <p><b>只有 INSERT 与 SELECT。</b>三张表都是只追加事实：
 * 没有 update、没有 delete，也没有"改一下载荷"的便捷方法。
 * 想改历史就得先回答"那已发布出去的事件算什么"——这个口子本轮不开。
 */
@Mapper
public interface JournalMapper {

    // ---------- 库存流水 ----------

    int insertLedger(InventoryLedger ledger);

    List<InventoryLedger> findLedgerOfRoom(@Param("tenantId") UUID tenantId,
                                           @Param("hotelId") UUID hotelId,
                                           @Param("roomId") UUID roomId);

    /** 某房间某天的净效果：用于核对与投影重建（S1-12）。 */
    int sumDelta(@Param("tenantId") UUID tenantId,
                 @Param("hotelId") UUID hotelId,
                 @Param("roomId") UUID roomId,
                 @Param("stayDate") LocalDate stayDate,
                 @Param("effectKind") EffectKind effectKind);

    // ---------- 审计 ----------

    int insertAudit(AuditLog audit);

    List<AuditLog> findAuditOfTarget(@Param("tenantId") UUID tenantId,
                                     @Param("targetType") String targetType,
                                     @Param("targetId") UUID targetId);

    // ---------- Outbox ----------

    int insertEvent(OutboxEvent event);

    List<OutboxEvent> findEventsOfAggregate(@Param("tenantId") UUID tenantId,
                                            @Param("aggregateType") String aggregateType,
                                            @Param("aggregateId") UUID aggregateId);
}
