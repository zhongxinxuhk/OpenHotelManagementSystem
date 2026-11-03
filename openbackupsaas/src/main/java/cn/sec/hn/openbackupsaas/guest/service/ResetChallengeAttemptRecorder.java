package cn.sec.hn.openbackupsaas.guest.service;

import cn.sec.hn.openbackupsaas.guest.mapper.GuestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 忘记密码重置挑战的失败尝试计数（R-20261103-01）。
 *
 * <p>与绑定（{@link ChallengeAttemptRecorder}）、登录（{@link LoginChallengeAttemptRecorder}）
 * 同一模式：独立事务（REQUIRES_NEW）——{@code completeReset} 比对失败会抛异常并
 * 回滚主事务，同事务的计数会被一起卷走，5 次上限形同虚设。
 * 三个 recorder 各管一张表，读的人不需要判断它数的是哪张。
 */
@Service
public class ResetChallengeAttemptRecorder {

    private final GuestMapper guestMapper;

    public ResetChallengeAttemptRecorder(GuestMapper guestMapper) {
        this.guestMapper = guestMapper;
    }

    /** 独立事务：无论外层 completeReset 如何回滚，这次失败都已计入。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID challengeId) {
        guestMapper.bumpResetChallengeAttempts(challengeId);
    }
}
