package cn.sec.hn.openbackupsaas.reservation.service;

import cn.sec.hn.openbackupsaas.reservation.domain.LineStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.Reservation;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationLine;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationQuery;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSummary;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationTransitions;
import cn.sec.hn.openbackupsaas.reservation.domain.RoomAssignment;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationMapper;
import cn.sec.hn.openbackupsaas.reservation.mapper.ReservationQueryMapper;
import cn.sec.hn.openbackupsaas.reservation.web.ReservationDetailView;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import cn.sec.hn.openbackupsaas.shared.web.PageView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 订单查询（S1-11）：只读，绝不改状态。
 *
 * <p>与命令侧（{@link ReservationCommandService}）分开的理由不是"架构好看"，而是：
 * 命令侧每条 SQL 都带 {@code FOR UPDATE} 与幂等记录，查询侧要的恰恰相反——
 * 不占锁、不做写事务、能安心走索引。把两者混在一起，早晚会有人在列表查询里
 * 复用带锁语句，然后在高并发下抱怨"翻页会卡住下单"。
 *
 * <p>{@code readOnly = true} 不只是文档：它让事务管理器跳过脏写检查，
 * 也明确告诉后来者这里不该出现写操作。
 */
@Service
public class ReservationQueryService {

    private final ReservationQueryMapper queryMapper;
    private final ReservationMapper reservationMapper;

    public ReservationQueryService(ReservationQueryMapper queryMapper, ReservationMapper reservationMapper) {
        this.queryMapper = queryMapper;
        this.reservationMapper = reservationMapper;
    }

    /**
     * 分页查询订单摘要。
     *
     * <p>列表与总数用同一段 where 条件（XML 里共享 {@code listFilter}），
     * 否则会出现"第 2 页有数据但 total 说只有 1 条"这种自相矛盾的分页。
     *
     * <p>先查总数再查列表：总数为 0 时直接返回，省掉一次必然为空的列表查询。
     */
    @Transactional(readOnly = true)
    public PageView<ReservationSummary> search(ReservationQuery query) {
        long total = queryMapper.countSummaries(query);
        if (total == 0) {
            return PageView.of(List.of(), query.page(), query.size(), 0);
        }
        List<ReservationSummary> items = queryMapper.findSummaries(query);
        return PageView.of(items, query.page(), query.size(), total);
    }

    /**
     * 订单详情。
     *
     * <p>租户与门店来自可信身份（调用方传入的是 {@code hotelAccess} 校验过的租户），
     * 三条查询都带 {@code tenantId + hotelId + reservationId}；查不到一律 404，
     * 不区分"不存在"与"不是你的"——后者会暴露别的租户有这张单。
     */
    @Transactional(readOnly = true)
    public ReservationDetailView detail(UUID tenantId, UUID hotelId, UUID reservationId) {
        Reservation reservation = reservationMapper.findReservation(tenantId, hotelId, reservationId)
                .orElseThrow(() -> new NotFoundException("订单不存在：" + reservationId));
        List<ReservationLine> lines = reservationMapper.findLinesByReservation(tenantId, hotelId, reservationId);
        List<RoomAssignment> assignments = queryMapper.findRoomAssignments(tenantId, hotelId, reservationId);
        return ReservationDetailView.of(reservation, lines, assignments, allowedActions(lines));
    }

    /**
     * 可执行动作：直接复用状态机守卫，而不是在前端或这里另写一套判断。
     *
     * <p>这样做的价值是"规则只有一份"：DEC-04 定案后若放开部分履约，
     * 守卫改了，详情里的按钮提示自动跟着改，不会出现"界面允许但接口 409"。
     * 返回的只是提示，服务端仍会在命令入口逐次校验。
     */
    private static List<String> allowedActions(List<ReservationLine> lines) {
        List<LineStatus> statuses = lines.stream().map(ReservationLine::status).toList();
        List<String> actions = new ArrayList<>(3);
        if (ReservationTransitions.confirmError(statuses) == null) {
            actions.add("CONFIRM");
        }
        if (ReservationTransitions.cancelError(statuses) == null) {
            actions.add("CANCEL");
        }
        if (ReservationTransitions.noShowError(statuses) == null) {
            actions.add("NO_SHOW");
        }
        return List.copyOf(actions);
    }
}
