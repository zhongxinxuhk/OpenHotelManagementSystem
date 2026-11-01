package cn.sec.hn.openbackupsaas.support;

import cn.sec.hn.openbackupsaas.command.domain.IdempotencyRecord;
import cn.sec.hn.openbackupsaas.identity.domain.Permission;
import cn.sec.hn.openbackupsaas.command.domain.IdempotencyStatus;
import cn.sec.hn.openbackupsaas.command.mapper.IdempotencyMapper;
import cn.sec.hn.openbackupsaas.identity.domain.AppUser;
import cn.sec.hn.openbackupsaas.identity.domain.Role;
import cn.sec.hn.openbackupsaas.identity.domain.SessionView;
import cn.sec.hn.openbackupsaas.identity.domain.UserSession;
import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.journal.domain.ActorType;
import cn.sec.hn.openbackupsaas.journal.domain.AuditLog;
import cn.sec.hn.openbackupsaas.journal.domain.EffectKind;
import cn.sec.hn.openbackupsaas.journal.domain.InventoryLedger;
import cn.sec.hn.openbackupsaas.journal.domain.OutboxEvent;
import cn.sec.hn.openbackupsaas.journal.mapper.JournalMapper;
import cn.sec.hn.openbackupsaas.reservation.domain.OccupancyStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.Reservation;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationLine;
import cn.sec.hn.openbackupsaas.reservation.domain.RoomOccupancy;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationExpiryMapper;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationMapper;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationQueryMapper;
import cn.sec.hn.openbackupsaas.room.domain.PhysicalRoom;
import cn.sec.hn.openbackupsaas.room.domain.RoomStatus;
import cn.sec.hn.openbackupsaas.room.domain.RoomType;
import cn.sec.hn.openbackupsaas.room.mapper.RoomMapper;
import cn.sec.hn.openbackupsaas.tenant.domain.Hotel;
import cn.sec.hn.openbackupsaas.tenant.domain.HotelGroup;
import cn.sec.hn.openbackupsaas.tenant.domain.OrgStatus;
import cn.sec.hn.openbackupsaas.tenant.domain.Tenant;
import cn.sec.hn.openbackupsaas.tenant.mapper.OrganizationMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 仅用于 {@code test} 档案：在不连接数据库时补齐 Mapper 依赖，让上下文装配检查可以运行。
 *
 * <p>为什么需要它：{@code test} 档案排除了 DataSource/Flyway/MyBatis 自动配置，
 * 因此 {@code @Mapper} 接口没有实现，服务类注入会失败。
 *
 * <p>这不是"用假实现测试业务"：任何方法被调用都会抛 {@link UnsupportedOperationException}，
 * 业务与数据库行为只能在 {@code integration} 档案（真实 PostgreSQL）下验证。
 */
@TestConfiguration(proxyBeanMethods = false)
public class NoDatabaseStubConfig {

    private static final String MESSAGE =
            "test 档案不提供数据访问实现：请用 integration 档案 + PMS_IT_DB=true 连真实 PostgreSQL 验证";

    @Bean
    OrganizationMapper organizationMapper() {
        return new OrganizationMapperStub();
    }

    @Bean
    IdentityMapper identityMapper() {
        return new IdentityMapperStub();
    }

    @Bean
    IdempotencyMapper idempotencyMapper() {
        return new IdempotencyMapperStub();
    }

    @Bean
    JournalMapper journalMapper() {
        return new JournalMapperStub();
    }

    @Bean
    RoomMapper roomMapper() {
        return new RoomMapperStub();
    }

    @Bean
    ReservationMapper reservationMapper() {
        return new ReservationMapperStub();
    }

    @Bean
    ReservationExpiryMapper reservationExpiryMapper() {
        return new ReservationExpiryMapperStub();
    }

    @Bean
    cn.sec.hn.openbackupsaas.reconcile.mapper.ReconcileMapper reconcileMapper() {
        return new ReconcileMapperStub();
    }

    @Bean
    cn.sec.hn.openbackupsaas.ops.mapper.OpsMapper opsMapper() {
        return new OpsMapperStub();
    }

    @Bean
    cn.sec.hn.openbackupsaas.integration.mapper.ExternalTaskMapper externalTaskMapper() {
        return new ExternalTaskMapperStub();
    }

    @Bean
    cn.sec.hn.openbackupsaas.inbox.mapper.InboxMapper inboxMapper() {
        return new InboxMapperStub();
    }

    @Bean
    cn.sec.hn.openbackupsaas.journal.mapper.OutboxPublisherMapper outboxPublisherMapper() {
        return new OutboxPublisherMapperStub();
    }

    @Bean
    ReservationQueryMapper reservationQueryMapper() {
        return new ReservationQueryMapperStub();
    }

    @Bean
    cn.sec.hn.openbackupsaas.inventory.mapper.InventoryMapper inventoryMapper() {
        return new InventoryMapperStub();
    }

    @Bean
    cn.sec.hn.openbackupsaas.guest.mapper.GuestMapper guestMapper() {
        return new GuestMapperStub();
    }

    @Bean
    cn.sec.hn.openbackupsaas.guest.mapper.GuestLoginPolicyMapper guestLoginPolicyMapper() {
        return new GuestLoginPolicyMapperStub();
    }

    @Bean
    cn.sec.hn.openbackupsaas.platform.mapper.PlatformMapper platformMapper() {
        return new PlatformMapperStub();
    }

    static final class JournalMapperStub implements JournalMapper {

        @Override
        public int insertLedger(InventoryLedger ledger) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<InventoryLedger> findLedgerOfRoom(UUID tenantId, UUID hotelId, UUID roomId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int sumDelta(UUID tenantId, UUID hotelId, UUID roomId, java.time.LocalDate stayDate,
                            EffectKind effectKind) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertAudit(AuditLog audit) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<AuditLog> findAuditOfTarget(UUID tenantId, String targetType, UUID targetId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertEvent(OutboxEvent event) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<OutboxEvent> findEventsOfAggregate(UUID tenantId, String aggregateType, UUID aggregateId) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

    static final class IdempotencyMapperStub implements IdempotencyMapper {

        @Override
        public int insertInProgress(IdempotencyRecord record) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertFailed(IdempotencyRecord record) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<IdempotencyRecord> findByScope(UUID tenantId, UUID hotelId, String callerNamespace,
                                                       String commandName, String idempotencyKey) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<IdempotencyRecord> findByOperation(UUID tenantId, UUID operationId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int markSucceeded(UUID tenantId, UUID operationId, UUID resultRef, String responseSnapshot,
                                 OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int markFailed(UUID tenantId, UUID operationId, String resultCode, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<IdempotencyStatus> statusOf(UUID tenantId, UUID operationId) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

    static final class OrganizationMapperStub implements OrganizationMapper {

        @Override
        public int insertTenant(Tenant tenant) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<Tenant> findTenantById(UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<Tenant> findTenantByCode(String code) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<Tenant> findAllTenants() {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertHotelGroup(HotelGroup group) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<HotelGroup> findHotelGroup(UUID tenantId, UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<HotelGroup> findHotelGroups(UUID tenantId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertHotel(Hotel hotel) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<Hotel> findHotel(UUID tenantId, UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<Hotel> findHotelByCode(UUID tenantId, String code) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<Hotel> findHotels(UUID tenantId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<Hotel> findHotelsByIds(UUID tenantId, List<UUID> ids) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

    static final class IdentityMapperStub implements IdentityMapper {

        @Override
        public int insertUser(AppUser user) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<AppUser> findUserByUsername(UUID tenantId, String username) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<AppUser> findUser(UUID tenantId, UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int updateLastLogin(UUID tenantId, UUID userId, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int updatePasswordHash(UUID tenantId, UUID userId, String passwordHash, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertRole(Role role) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<Role> findRoleByCode(UUID tenantId, String code) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int grantPermission(UUID tenantId, UUID roleId, String permissionCode) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int assignRole(UUID tenantId, UUID userId, UUID roleId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<String> findPermissionCodes(UUID tenantId, UUID userId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int addHotelScope(UUID tenantId, UUID userId, UUID hotelId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<UUID> findHotelScopeIds(UUID tenantId, UUID userId) {
            throw new UnsupportedOperationException(MESSAGE);
        }
        @Override
        public int clearHotelScopes(UUID tenantId, UUID userId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int clearUserRoles(UUID tenantId, UUID userId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public long countOtherActiveUsersWithPermission(UUID tenantId, String permissionCode,
                                                       UUID excludedUserId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int requirePasswordChange(UUID tenantId, UUID userId, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<AppUser> findUsers(UUID tenantId, String status, String keyword, int limit, int offset) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public long countUsers(UUID tenantId, String status, String keyword) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int updateUserStatus(UUID tenantId, UUID userId, String status, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<Role> findRole(UUID tenantId, UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<Role> findRoles(UUID tenantId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int updateRoleStatus(UUID tenantId, UUID roleId, String status, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<String> findRolePermissionCodes(UUID tenantId, UUID roleId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int revokeRolePermission(UUID tenantId, UUID roleId, String permissionCode) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<Permission> findAllPermissions() {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<Role> findRolesByUser(UUID tenantId, UUID userId) {
            throw new UnsupportedOperationException(MESSAGE);
        }


        @Override
        public int insertSession(UserSession session) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<SessionView> findUsableSession(String tokenHash) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int revokeSession(UUID tenantId, UUID sessionId, OffsetDateTime at, String reason) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int revokeUserSessions(UUID tenantId, UUID userId, OffsetDateTime at, String reason,
                                      UUID exceptSessionId) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

    static final class RoomMapperStub implements RoomMapper {

        @Override
        public int insertRoomType(RoomType roomType) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<RoomType> findRoomType(UUID tenantId, UUID hotelId, UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<RoomType> findRoomTypeByCode(UUID tenantId, UUID hotelId, String code) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<RoomType> findRoomTypes(UUID tenantId, UUID hotelId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int updateRoomTypeStatus(UUID tenantId, UUID hotelId, UUID id, OrgStatus status,
                                        OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int countRoomsByStatus(UUID tenantId, UUID hotelId, UUID roomTypeId, RoomStatus status) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertRoom(PhysicalRoom room) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<PhysicalRoom> findRoom(UUID tenantId, UUID hotelId, UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<PhysicalRoom> findRoomByCode(UUID tenantId, UUID hotelId, String code) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<PhysicalRoom> findRooms(UUID tenantId, UUID hotelId, UUID roomTypeId,
                                            RoomStatus status, Integer floor) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int updateRoomAttributes(UUID tenantId, UUID hotelId, UUID id, UUID roomTypeId,
                                        Integer floor, String notes, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int updateRoomStatus(UUID tenantId, UUID hotelId, UUID id, RoomStatus status, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

    static final class ReservationMapperStub implements ReservationMapper {

        @Override
        public int insertReservation(Reservation reservation) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<Reservation> findReservation(UUID tenantId, UUID hotelId, UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<Reservation> lockReservation(UUID tenantId, UUID hotelId, UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertLine(ReservationLine line) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<ReservationLine> findLine(UUID tenantId, UUID hotelId, UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertOccupancy(RoomOccupancy occupancy) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<RoomOccupancy> findOccupancy(UUID tenantId, UUID hotelId, UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<RoomOccupancy> findOccupanciesOfRoom(UUID tenantId, UUID hotelId, UUID roomId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<PhysicalRoom> findAvailableRooms(UUID tenantId, UUID hotelId, UUID roomTypeId,
                                                    StayRange range) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int updateOccupancyStatus(UUID tenantId, UUID hotelId, UUID id, OccupancyStatus status,
                                         OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<ReservationLine> findLinesByReservation(UUID tenantId, UUID hotelId, UUID reservationId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<RoomOccupancy> findOccupanciesByReservation(UUID tenantId, UUID hotelId, UUID reservationId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int updateLineStatus(UUID tenantId, UUID hotelId, UUID id,
                                   cn.sec.hn.openbackupsaas.reservation.domain.LineStatus status,
                                   OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int updateReservationStatus(UUID tenantId, UUID hotelId, UUID id,
                                          cn.sec.hn.openbackupsaas.reservation.domain.ReservationStatus status,
                                          OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<PhysicalRoom> lockRooms(UUID tenantId, UUID hotelId, List<UUID> roomIds) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

    static final class ReservationExpiryMapperStub implements ReservationExpiryMapper {

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.reservation.domain.DueReservation> claimDueReservations(
                int batchSize) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int expireIfPending(UUID tenantId, UUID hotelId, UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public cn.sec.hn.openbackupsaas.reservation.domain.HoldDueState checkHoldDue(
                UUID tenantId, UUID hotelId, UUID reservationId) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

    static final class ReservationQueryMapperStub implements ReservationQueryMapper {

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.reservation.domain.ReservationSummary> findSummaries(
                cn.sec.hn.openbackupsaas.reservation.domain.ReservationQuery query) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public long countSummaries(cn.sec.hn.openbackupsaas.reservation.domain.ReservationQuery query) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.reservation.domain.RoomAssignment> findRoomAssignments(
                UUID tenantId, UUID hotelId, UUID reservationId) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

    static final class InventoryMapperStub implements cn.sec.hn.openbackupsaas.inventory.mapper.InventoryMapper {

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.inventory.domain.InventoryDay> findDailyAvailability(
                cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow window) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.inventory.domain.BookableRoomType> findBookableRooms(
                cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow window) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.inventory.domain.ExpectedProjectionRow>
                computeExpectedProjection(cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow window) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.inventory.domain.StoredProjectionRow>
                findStoredProjection(cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow window) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int deleteProjection(cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow window) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int rebuildProjection(cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow window) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.inventory.mapper.InventoryMapper.LedgerMismatch>
                findLedgerMismatches(cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow window) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

    static final class OutboxPublisherMapperStub
            implements cn.sec.hn.openbackupsaas.journal.mapper.OutboxPublisherMapper {

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.journal.domain.PendingOutboxEvent> claimBatch(
                UUID leaseToken, long leaseSeconds, int batchSize) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int markPublished(UUID eventId, UUID leaseToken) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int markFailed(UUID eventId, UUID leaseToken, String error, long backoffSeconds) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public long countPending() {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Long oldestPendingSeconds() {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

    static final class InboxMapperStub implements cn.sec.hn.openbackupsaas.inbox.mapper.InboxMapper {

        @Override
        public int recordIfAbsent(String consumerName,
                                  cn.sec.hn.openbackupsaas.inbox.domain.InboxMessage message,
                                  cn.sec.hn.openbackupsaas.inbox.domain.InboxOutcome outcome,
                                  String detail) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public String findOutcome(String consumerName, UUID eventId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public cn.sec.hn.openbackupsaas.inbox.mapper.InboxMapper.StaySpan findStaySpan(
                UUID tenantId, UUID hotelId, UUID reservationId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public cn.sec.hn.openbackupsaas.inbox.domain.InboxPosition findPosition(
                String consumerName, UUID tenantId, String aggregateType, UUID aggregateId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int advancePosition(String consumerName, UUID tenantId, String aggregateType,
                                   UUID aggregateId, long version, int ordinal) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.inbox.domain.InboxMessage> findParked(
                String consumerName, int limit) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int settleParked(String consumerName, UUID eventId,
                                cn.sec.hn.openbackupsaas.inbox.domain.InboxOutcome outcome, String detail) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public long countParked(String consumerName) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

    static final class ExternalTaskMapperStub
            implements cn.sec.hn.openbackupsaas.integration.mapper.ExternalTaskMapper {

        @Override
        public UUID insertTask(UUID tenantId, UUID hotelId, String taskType, String channel,
                               String idempotencyKey, String payload, int maxAttempts) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.integration.domain.ExternalTask> claimBatch(
                UUID leaseToken, long leaseSeconds, int batchSize) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertAttempt(UUID taskId, int attemptNo, java.time.OffsetDateTime startedAt,
                                 java.time.OffsetDateTime finishedAt, String outcome,
                                 cn.sec.hn.openbackupsaas.integration.domain.ErrorClass errorClass,
                                 String detail, String resultRef) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int markAttempts(UUID taskId, int attemptNo,
                                cn.sec.hn.openbackupsaas.integration.domain.ErrorClass errorClass,
                                String detail) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int settleSucceeded(UUID taskId, UUID leaseToken, String resultRef) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int settleRetry(UUID taskId, UUID leaseToken,
                               cn.sec.hn.openbackupsaas.integration.domain.ErrorClass errorClass,
                               String detail, java.time.OffsetDateTime nextAttemptAt) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int settleFailed(UUID taskId, UUID leaseToken,
                                cn.sec.hn.openbackupsaas.integration.domain.ErrorClass errorClass,
                                String detail) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int settleManual(UUID taskId, UUID leaseToken,
                                cn.sec.hn.openbackupsaas.integration.domain.ErrorClass errorClass,
                                String detail) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int settleDead(UUID taskId, UUID leaseToken,
                              cn.sec.hn.openbackupsaas.integration.domain.ErrorClass errorClass,
                              String detail) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int release(UUID taskId, UUID leaseToken,
                           java.time.OffsetDateTime nextAttemptAt, String reason) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public cn.sec.hn.openbackupsaas.integration.domain.ExternalTask findById(UUID taskId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int settleManualResolution(UUID taskId, String status, String resultRef, String detail) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<java.util.Map<String, Object>> findAttempts(UUID taskId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.integration.domain.ExternalTask> findByStatus(
                String status, int limit) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

    static final class OpsMapperStub implements cn.sec.hn.openbackupsaas.ops.mapper.OpsMapper {

        @Override
        public UUID insertDeadLetter(UUID tenantId, String source, String messageBody, String error) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public cn.sec.hn.openbackupsaas.ops.domain.DeadLetter findDeadLetter(UUID deadLetterId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.ops.domain.DeadLetter> findPendingDeadLetters(int limit) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int settleDeadLetter(UUID deadLetterId, String status, String detail) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int replayExternalTask(UUID taskId, int extraAttempts, String reason) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertIntervention(cn.sec.hn.openbackupsaas.ops.domain.OpsIntervention intervention) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.ops.domain.OpsIntervention> findRecentInterventions(int limit) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public long countExternalTasksByStatus(java.util.List<String> statuses) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public long countPendingDeadLetters() {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public long countParkedInboxEvents() {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.Map<String, Object> findExternalTaskMeta(UUID taskId) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

    static final class ReconcileMapperStub
            implements cn.sec.hn.openbackupsaas.reconcile.mapper.ReconcileMapper {

        @Override
        public UUID insertRun(UUID tenantId, UUID hotelId, java.time.LocalDate from,
                              java.time.LocalDate to, String triggerSource, UUID triggeredBy) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int finishRun(UUID runId, int newFindings, int repeatedFindings, int repairedFindings,
                             int supersededFindings, int openFindings) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int failRun(UUID runId, String error) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int failStaleRuns(UUID tenantId, UUID hotelId, long olderThanSeconds) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public cn.sec.hn.openbackupsaas.reconcile.domain.ReconcileRunView findRun(
                UUID tenantId, UUID hotelId, UUID runId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.reconcile.domain.ReconcileRunView> findRecentRuns(
                UUID tenantId, UUID hotelId, int limit) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Upserted upsertFinding(UUID tenantId, UUID hotelId,
                                     cn.sec.hn.openbackupsaas.reconcile.domain.RawFinding finding,
                                     cn.sec.hn.openbackupsaas.reconcile.domain.FindingSeverity severity,
                                     UUID runId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int supersedeStale(UUID tenantId, UUID hotelId, UUID runId,
                                  java.time.LocalDate from, java.time.LocalDate to) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int markRepaired(UUID tenantId, UUID hotelId, UUID findingId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int markIgnored(UUID tenantId, UUID hotelId, UUID findingId, UUID operator, String reason) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public cn.sec.hn.openbackupsaas.reconcile.domain.FindingView findFinding(
                UUID tenantId, UUID hotelId, UUID findingId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.reconcile.domain.FindingView> findOpenFindings(
                UUID tenantId, UUID hotelId,
                cn.sec.hn.openbackupsaas.reconcile.domain.FindingSeverity severity, int limit) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.reconcile.domain.FindingView> findSettledFindings(
                UUID tenantId, UUID hotelId, int limit) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public long countOpenBySeverity(UUID tenantId, UUID hotelId,
                                        cn.sec.hn.openbackupsaas.reconcile.domain.FindingSeverity severity) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.time.LocalDate findWatermark(UUID tenantId, UUID hotelId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int advanceWatermark(UUID tenantId, UUID hotelId, java.time.LocalDate scannedThrough) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.reconcile.mapper.ReconcileMapper.HotelRef> findActiveHotels(
                int limit) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.reconcile.mapper.ReconcileMapper.StatusMismatch> findStatusMismatches(
                cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow window) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.reconcile.mapper.ReconcileMapper.MissingOccupancy> findMissingOccupancies(
                cn.sec.hn.openbackupsaas.inventory.domain.InventoryWindow window) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public cn.sec.hn.openbackupsaas.reconcile.mapper.ReconcileMapper.BacklogStat outboxBacklog(
                UUID tenantId, UUID hotelId, long olderThanSeconds) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public cn.sec.hn.openbackupsaas.reconcile.mapper.ReconcileMapper.BacklogStat inboxParked(
                UUID tenantId, UUID hotelId, long olderThanSeconds) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }
    /**
     * 住客 Mapper 桩。
     *
     * <p>住客表是全库唯一没有 tenant_id 的业务表（ADR-018 §2.6），
     * 所以这里的方法也都不带租户参数——与生产接口保持一致，
     * 避免桩与真实签名漂移导致"测试档案能过、集成档案编译不过"。
     */
    static final class GuestMapperStub implements cn.sec.hn.openbackupsaas.guest.mapper.GuestMapper {

        @Override
        public int insertAccount(cn.sec.hn.openbackupsaas.guest.domain.GuestAccount account) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<cn.sec.hn.openbackupsaas.guest.domain.GuestAccount> findAccount(UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertCredential(UUID id, UUID accountId, String provider, String subject,
                                    String secretHash, OffsetDateTime verifiedAt,
                                    OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<cn.sec.hn.openbackupsaas.guest.domain.UsernameCredential> findUsernameCredential(
                String subject) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<UUID> findAccountIdByCredential(String provider, String subject) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int markCredentialVerified(UUID accountId, String provider, String subject,
                                          OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertVerification(UUID accountId, String idNumberEnc, String idNumberHmac,
                                      String idNumberMask, String realNameEnc, String realNameMask,
                                      OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int updateVerification(UUID accountId, String idNumberEnc, String idNumberHmac,
                                      String idNumberMask, String realNameEnc, String realNameMask,
                                      OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<cn.sec.hn.openbackupsaas.guest.domain.GuestVerification> findVerification(
                UUID accountId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<UUID> findAccountIdByIdNumberHmac(String idNumberHmac) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertSession(cn.sec.hn.openbackupsaas.guest.domain.GuestSession session) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<cn.sec.hn.openbackupsaas.guest.domain.GuestSessionView> findUsableSession(
                String tokenHash) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int revokeSession(UUID sessionId, String reason, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int revokeAllSessionsOfAccount(UUID accountId, String reason,
                                              OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.guest.domain.PendingVerification>
                findPendingVerifications(int limit, int offset) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public long countPendingVerifications() {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int applyVerificationDecision(UUID accountId, String decision,
                                             OffsetDateTime verifiedAt, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertReview(cn.sec.hn.openbackupsaas.guest.domain.GuestVerificationReview review) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.guest.domain.GuestVerificationReview>
                findReviews(UUID accountId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertChallenge(UUID id, UUID accountId, String provider, String subject,
                                   String codeHash, OffsetDateTime expiresAt, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<cn.sec.hn.openbackupsaas.guest.domain.OwnershipChallenge> findChallenge(
                UUID id) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int bumpChallengeAttempts(UUID id, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int consumeChallenge(UUID id, int maxAttempts, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.guest.domain.GuestCredentialView>
                findCredentialsOfAccount(UUID accountId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public long countCredentials(UUID accountId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int deleteCredential(UUID accountId, String provider, String subject) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

    /**
     * 平台 Mapper 桩（ADR-019）。
     *
     * <p>与其它桩一样：`test` 档案不连库，所以方法体一律抛。存在的意义只是
     * 让 Spring 上下文能起来——漏了它，`ApplicationContextTests` 与
     * `OpenApiContractTest` 会以 "No qualifying bean of type ..." 失败，
     * 而那条报错很难让人联想到"该补桩了"。
     */
    static final class PlatformMapperStub
            implements cn.sec.hn.openbackupsaas.platform.mapper.PlatformMapper {

        @Override
        public Optional<cn.sec.hn.openbackupsaas.platform.domain.PlatformUser> findUserByUsername(
                String username) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<String> findActions(UUID userId) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int updateLastLogin(UUID userId, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int updatePasswordHash(UUID userId, String passwordHash, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertSession(cn.sec.hn.openbackupsaas.platform.domain.PlatformSession session) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<cn.sec.hn.openbackupsaas.platform.domain.PlatformSessionView> findUsableSession(
                String tokenHash) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int revokeSession(UUID sessionId, String reason, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int revokeOtherSessions(UUID userId, UUID keepSessionId, String reason,
                                       OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.guest.domain.VerificationChannel> findChannels() {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Optional<cn.sec.hn.openbackupsaas.guest.domain.VerificationChannel> findChannelByCode(
                String code) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int insertChannel(cn.sec.hn.openbackupsaas.guest.domain.VerificationChannel channel) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int updateChannelConfig(String code, String name, String endpoint,
                                       String credentialRef, int timeoutMs, int priority,
                                       String status, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int resetCircuit(String code, String reason, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

    /** 住客登录方式开关桩（ADR-019 §2.9）。 */
    static final class GuestLoginPolicyMapperStub
            implements cn.sec.hn.openbackupsaas.guest.mapper.GuestLoginPolicyMapper {

        @Override
        public Optional<cn.sec.hn.openbackupsaas.guest.domain.GuestLoginPolicy> find(String provider) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public java.util.List<cn.sec.hn.openbackupsaas.guest.domain.GuestLoginPolicy> findAll() {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public int upsert(String provider, boolean enabled, String updatedBy, OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }

}
