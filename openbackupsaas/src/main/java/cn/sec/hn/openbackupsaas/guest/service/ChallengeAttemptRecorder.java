package cn.sec.hn.openbackupsaas.guest.service;

import cn.sec.hn.openbackupsaas.guest.mapper.GuestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 归属挑战的失败尝试计数（ADR-019 §2.10）。
 *
 * <h2>为什么必须是独立事务（REQUIRES_NEW）</h2>
 * {@code confirm} 在自己的事务里比对验证码，错了会抛异常——而抛异常会
 * <b>回滚整个事务</b>。如果"attempts + 1"发生在同一个事务里，
 * 它会跟异常一起被回滚：计数永远停在 0，5 次上限形同虚设，
 * 6 位验证码可以被无限次在线爆破。
 *
 * <p>这是"失败也要落库"的典型场景：失败记录恰恰要在主流程失败时存活下来。
 * 同类问题以后还会遇到（登录失败计数、风控计数），处理方式相同——
 * 计数走 REQUIRES_NEW，且不能同类自调用（Spring 代理对自调用不生效）。
 */
@Service
public class ChallengeAttemptRecorder {

    private final GuestMapper guestMapper;
    private final Clock clock;

    public ChallengeAttemptRecorder(GuestMapper guestMapper, Clock clock) {
        this.guestMapper = guestMapper;
        this.clock = clock;
    }

    /** 独立事务：无论外层 confirm 如何回滚，这次失败都已计入。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID challengeId) {
        guestMapper.bumpChallengeAttempts(challengeId, OffsetDateTime.now(clock));
    }
}
