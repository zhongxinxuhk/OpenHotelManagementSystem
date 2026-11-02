package cn.sec.hn.openbackupsaas.guest.service;

import cn.sec.hn.openbackupsaas.guest.mapper.GuestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 验证码**登录**挑战的失败尝试计数（R-20261102-07）。
 *
 * <p>与 {@link ChallengeAttemptRecorder}（绑定挑战）分开：两张表、两个 UPDATE，
 * 合在一个类里会出现"参数带表名"或"两个方法各写一遍"的形状——
 * 分开各自一行调用，读的人不需要判断它数的是哪张表。
 *
 * <p>独立事务（REQUIRES_NEW）的理由与绑定侧完全同源：{@code loginWithCode}
 * 比对失败会抛异常并回滚主事务，同事务的计数会被一起卷走，
 * 5 次上限形同虚设。失败记录恰恰要在主流程失败时存活下来。
 */
@Service
public class LoginChallengeAttemptRecorder {

    private final GuestMapper guestMapper;

    public LoginChallengeAttemptRecorder(GuestMapper guestMapper) {
        this.guestMapper = guestMapper;
    }

    /** 独立事务：无论外层 loginWithCode 如何回滚，这次失败都已计入。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID challengeId) {
        guestMapper.bumpLoginChallengeAttempts(challengeId);
    }
}
