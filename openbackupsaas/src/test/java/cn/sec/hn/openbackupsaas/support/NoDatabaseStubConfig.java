package cn.sec.hn.openbackupsaas.support;

import cn.sec.hn.openbackupsaas.identity.domain.AppUser;
import cn.sec.hn.openbackupsaas.identity.domain.Role;
import cn.sec.hn.openbackupsaas.identity.domain.SessionView;
import cn.sec.hn.openbackupsaas.identity.domain.UserSession;
import cn.sec.hn.openbackupsaas.identity.mapper.IdentityMapper;
import cn.sec.hn.openbackupsaas.reservation.domain.OccupancyStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.Reservation;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationLine;
import cn.sec.hn.openbackupsaas.reservation.domain.RoomOccupancy;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationMapper;
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
    RoomMapper roomMapper() {
        return new RoomMapperStub();
    }

    @Bean
    ReservationMapper reservationMapper() {
        return new ReservationMapperStub();
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
        public int updateOccupancyStatus(UUID tenantId, UUID hotelId, UUID id, OccupancyStatus status,
                                         OffsetDateTime at) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public List<PhysicalRoom> lockRooms(UUID tenantId, UUID hotelId, List<UUID> roomIds) {
            throw new UnsupportedOperationException(MESSAGE);
        }
    }
}
