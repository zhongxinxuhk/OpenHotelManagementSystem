package cn.sec.hn.openbackupsaas.reservation.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 状态机守卫纯函数测试（无数据库）：确认 / 取消 / No-show 的合法迁移与拒绝原因。
 */
class ReservationTransitionsTest {

    @Test
    void confirmRequiresAllPending() {
        assertNull(ReservationTransitions.confirmError(List.of(LineStatus.PENDING)));
        assertNotNull(ReservationTransitions.confirmError(List.of(LineStatus.CONFIRMED)));
        assertNotNull(ReservationTransitions.confirmError(List.of(LineStatus.CANCELLED)));
        assertNotNull(ReservationTransitions.confirmError(List.of(LineStatus.CHECKED_IN)));
    }

    @Test
    void cancelRejectsCheckedInButAllowsPendingOrConfirmed() {
        assertNull(ReservationTransitions.cancelError(List.of(LineStatus.PENDING)));
        assertNull(ReservationTransitions.cancelError(List.of(LineStatus.CONFIRMED)));
        assertNull(ReservationTransitions.cancelError(List.of(LineStatus.PENDING, LineStatus.CONFIRMED)));

        String checkedIn = ReservationTransitions.cancelError(List.of(LineStatus.CHECKED_IN));
        assertNotNull(checkedIn, "已入住不应被普通取消释放");
        assertNotNull(ReservationTransitions.cancelError(List.of(LineStatus.NO_SHOW)));
        assertNotNull(ReservationTransitions.cancelError(List.of(LineStatus.CANCELLED)));
    }

    @Test
    void noShowRequiresAllConfirmed() {
        assertNull(ReservationTransitions.noShowError(List.of(LineStatus.CONFIRMED)));
        assertNotNull(ReservationTransitions.noShowError(List.of(LineStatus.PENDING)));
        assertNotNull(ReservationTransitions.noShowError(List.of(LineStatus.CHECKED_IN)));
    }

    @Test
    void emptyLinesAreRejected() {
        assertNotNull(ReservationTransitions.confirmError(List.of()));
        assertNotNull(ReservationTransitions.cancelError(List.of()));
        assertNotNull(ReservationTransitions.noShowError(List.of()));
    }
}
