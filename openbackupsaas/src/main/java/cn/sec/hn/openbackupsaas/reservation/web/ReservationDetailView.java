package cn.sec.hn.openbackupsaas.reservation.web;

import cn.sec.hn.openbackupsaas.reservation.domain.LineStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.OccupancyStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.Reservation;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationLine;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationSourceType;
import cn.sec.hn.openbackupsaas.reservation.domain.ReservationStatus;
import cn.sec.hn.openbackupsaas.reservation.domain.RoomAssignment;
import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 订单详情视图（S1-11）。
 *
 * <p>三处刻意的取舍：
 * <ol>
 *   <li><b>不带 {@code tenantId} / 占用 id / operationId。</b>这些是服务端实现事实，
 *       下发只会扩大调用方的推断面（例如用占用 id 去试探别的接口）。</li>
 *   <li><b>带 {@code allowedActions}。</b>可执行动作由状态机守卫在同一份代码里算出，
 *       前端不必自己复刻一遍 {@code PENDING→CONFIRMED} 的规则；规则变了前端自动跟着变，
 *       也不会出现"按钮能点但接口 409"的错位。这只是提示，服务端仍然逐次校验。</li>
 *   <li><b>带 {@code version}。</b>确认/取消支持期望版本校验，客户端要拿到它。</li>
 * </ol>
 */
public record ReservationDetailView(
        UUID id,
        String reservationNo,
        ReservationSourceType sourceType,
        ReservationStatus status,
        Long version,
        StayRange stayRange,
        List<DetailLineView> lines,
        List<String> allowedActions,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /**
     * 订单行：房型需求 + 实际分配到的房间（未分配时为 null，不编造占位值）。
     *
     * <p>类名带 Detail 前缀不是啰嗦：springdoc 用<b>简单类名</b>做 components.schemas 的 key，
     * 两个不同包的同名嵌套 record 会被合并成一份 schema。这里曾与 {@code ReservationView.LineView}
     * 撞名，导致 OpenAPI 里详情的 lines 指向了创建响应的字段（含 occupancyId），
     * 契约是错的而运行时是对的——这种错只有契约测试能发现。
     */
    public record DetailLineView(
            UUID lineId,
            Integer lineNo,
            UUID requestedRoomTypeId,
            LineStatus status,
            StayRange plannedRange,
            UUID assignedRoomId,
            String assignedRoomCode,
            OccupancyStatus occupancyStatus
    ) {

        static DetailLineView of(ReservationLine line, RoomAssignment assignment) {
            return new DetailLineView(line.id(), line.lineNo(), line.requestedRoomTypeId(), line.status(),
                    line.plannedRange(),
                    assignment == null ? null : assignment.roomId(),
                    assignment == null ? null : assignment.roomCode(),
                    assignment == null ? null : assignment.occupancyStatus());
        }
    }

    public static ReservationDetailView of(Reservation reservation, List<ReservationLine> lines,
                                           List<RoomAssignment> assignments, List<String> allowedActions) {
        List<DetailLineView> lineViews = lines.stream()
                .map(line -> DetailLineView.of(line, assignments.stream()
                        .filter(a -> a.lineId().equals(line.id()))
                        .findFirst()
                        .orElse(null)))
                .toList();
        return new ReservationDetailView(reservation.id(), reservation.reservationNo(),
                reservation.sourceType(), reservation.status(), reservation.version(),
                overallRange(lines), lineViews, allowedActions,
                reservation.createdAt(), reservation.updatedAt());
    }

    /** 整单跨度：最早入住日到最晚离店日；多行时取并集而不是第一行。 */
    private static StayRange overallRange(List<ReservationLine> lines) {
        if (lines.isEmpty()) {
            return null;
        }
        return lines.stream()
                .map(ReservationLine::plannedRange)
                .reduce((a, b) -> StayRange.of(
                        a.checkIn().isBefore(b.checkIn()) ? a.checkIn() : b.checkIn(),
                        a.checkOut().isAfter(b.checkOut()) ? a.checkOut() : b.checkOut()))
                .orElse(null);
    }
}
