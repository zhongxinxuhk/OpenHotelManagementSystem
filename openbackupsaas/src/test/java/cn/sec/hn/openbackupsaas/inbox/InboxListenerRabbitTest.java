package cn.sec.hn.openbackupsaas.inbox;

import cn.sec.hn.openbackupsaas.inbox.mapper.InboxMapper;
import cn.sec.hn.openbackupsaas.journal.messaging.EventPublisher;
import cn.sec.hn.openbackupsaas.journal.messaging.OutboxEnvelope;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.service.ReservationCommandService;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.service.RoomService;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Broker 上的端到端消费（S2-02）。
 *
 * <p>前面那些用例直接调用 {@code InboxProcessor}，验证的是"判定逻辑对不对"；
 * 这里验证的是<b>接线对不对</b>：交换机与队列绑定是否生效、消息体能否被解析、
 * 监听容器是否真的把消息交给了消费者、消费结果是否落到 Inbox 台账并刷新投影。
 * 这几件事任何一件断了，处理器再正确也不会有消息进来。
 *
 * <p>需要真实 RabbitMQ（{@code PMS_IT_RABBIT=true}）；CI 由 service 提供，本地用容器。
 * 连接参数走 {@code PMS_RABBIT_HOST/PORT}，与应用配置同源。
 */
@TestPropertySource(properties = {
        "pms.inbox.enabled=true",
        "pms.inbox.queue=pms.inbox.it",
        "pms.inbox.routing-keys=ReservationCreated,ReservationConfirmed,ReservationCancelled,"
                + "ReservationNoShow,ReservationExpired"
})
@EnabledIfEnvironmentVariable(named = "PMS_IT_RABBIT", matches = "true")
class InboxListenerRabbitTest extends PostgresIntegrationTest {

    private static final LocalDate IN = LocalDate.of(2026, 12, 1);

    @Autowired
    private EventPublisher eventPublisher;
    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private InboxMapper inboxMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private RoomService roomService;
    @Autowired
    private ReservationCommandService reservationCommandService;

    @Test
    void publishedEventIsConsumedByListenerAndRecordedInInbox() throws Exception {
        Tenant tenant = organizationService.createTenant("t-listen", "监听测试租户");
        Hotel hotel = organizationService.createHotel(tenant.id(), null, "h-listen", "主店", "Asia/Shanghai");
        RoomType type = roomService.createRoomType(tenant.id(), hotel.id(), "std", "标准间", 2, 0, null);
        roomService.createRoom(tenant.id(), hotel.id(), type.id(), "0201", 1, null);
        reservationCommandService.createReservation(tenant.id(),
                new ReservationCommandService.CreateCommand(hotel.id(), "listen-client",
                        "create-" + UUID.randomUUID(), ReservationSourceType.FRONTDESK,
                        List.of(new ReservationCommandService.CreateCommand.LineCommand(
                                type.id(), StayRange.of(IN, IN.plusDays(2))))));

        // 两件事都必须先确定，否则发布会撞上"交换机存在但没有绑定"：
        // 1) 队列/交换机/绑定真的声明到位（RabbitAdmin 的自动声明在连接建立时触发，与容器启动有竞态，
        //    所以这里显式 initialize 一次，把它变成确定动作）；
        // 2) 监听容器已经在跑。
        amqpAdmin.initialize();
        awaitListenerRunning();

        OutboxEnvelope envelope = latestEvent(tenant.id(), hotel.id());
        eventPublisher.publish(envelope);

        // 消费是异步的：轮询到"已处理"为止，而不是 sleep 一个拍脑袋的时长
        UUID eventId = envelope.eventId();
        String outcome = awaitOutcome(eventId, 20);

        assertEquals("PROCESSED", outcome, "事件应被监听器消费并标记为已处理");
        assertEquals(envelope.tenantId(), jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM inbox_event WHERE event_id = ?", UUID.class, eventId),
                "台账里的租户必须来自消息体");
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM room_inventory_day WHERE tenant_id = ? AND reservation_unavailable "
                        + "AND stay_date >= ? AND stay_date < ?",
                Integer.class, tenant.id(), IN, IN.plusDays(2)),
                "消费成功后投影应已被重算（两晚各一行）");
        assertEquals(0, inboxMapper.countParked("room-inventory-projection"));
    }

    private void awaitListenerRunning() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            boolean running = listenerRegistry.getListenerContainers().stream()
                    .anyMatch(container -> container.isRunning());
            if (running) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("监听容器 30 秒内未启动：检查 Broker 是否可达、队列是否声明成功");
    }

    private String awaitOutcome(UUID eventId, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            List<String> outcomes = jdbcTemplate.queryForList(
                    "SELECT outcome FROM inbox_event WHERE event_id = ?", String.class, eventId);
            if (!outcomes.isEmpty()) {
                return outcomes.get(0);
            }
            Thread.sleep(200);
        }
        return null;
    }

    private OutboxEnvelope latestEvent(UUID tenantId, UUID hotelId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT event_id, tenant_id, hotel_id, aggregate_type, aggregate_id, aggregate_version, "
                        + "event_ordinal, event_type, schema_version, payload::text AS payload, occurred_at "
                        + "FROM outbox_event WHERE tenant_id = ? AND hotel_id = ? "
                        + "ORDER BY occurred_at DESC LIMIT 1", tenantId, hotelId);
        OffsetDateTime occurredAt = ((java.sql.Timestamp) row.get("occurred_at")).toInstant()
                .atOffset(java.time.ZoneOffset.UTC);
        return new OutboxEnvelope(
                (UUID) row.get("event_id"),
                (UUID) row.get("tenant_id"),
                (UUID) row.get("hotel_id"),
                (String) row.get("aggregate_type"),
                (UUID) row.get("aggregate_id"),
                ((Number) row.get("aggregate_version")).longValue(),
                ((Number) row.get("event_ordinal")).intValue(),
                (String) row.get("event_type"),
                ((Number) row.get("schema_version")).intValue(),
                (String) row.get("payload"),
                occurredAt);
    }
}
