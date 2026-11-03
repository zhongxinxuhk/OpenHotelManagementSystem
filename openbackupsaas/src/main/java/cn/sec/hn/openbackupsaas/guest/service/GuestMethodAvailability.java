package cn.sec.hn.openbackupsaas.guest.service;

import cn.sec.hn.openbackupsaas.guest.domain.GuestLoginPolicy;
import cn.sec.hn.openbackupsaas.guest.domain.GuestProvider;
import cn.sec.hn.openbackupsaas.guest.mapper.GuestLoginPolicyMapper;
import cn.sec.hn.openbackupsaas.shared.error.LoginMethodUnavailableException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 住客登录方式的可用性判定（ADR-019 §2.9 的唯一实现点）。
 *
 * <p>从 {@code GuestAuthenticationService} 提取出来：登录与凭证绑定都要问
 * "这个方式现在能不能用"，两处各写一份迟早出现细微差别（比如一处忘了
 * 检查实现状态）。判定规则只有一条：**实现状态（代码）∧ 策略开关（控制台）**。
 */
@Service
public class GuestMethodAvailability {

    private final GuestLoginPolicyMapper loginPolicyMapper;

    public GuestMethodAvailability(GuestLoginPolicyMapper loginPolicyMapper) {
        this.loginPolicyMapper = loginPolicyMapper;
    }

    /** 可用 = 已实现 ∧ 策略开启。策略行缺失按禁用（缺行只可能是误删，宁可拦下让人来问）。 */
    @Transactional(readOnly = true)
    public boolean isAvailable(GuestProvider provider) {
        boolean enabled = loginPolicyMapper.find(provider.name())
                .map(GuestLoginPolicy::enabled)
                .orElse(false);
        return enabled && provider.isImplemented();
    }

    /** 不可用时抛 403"登录方式未开放"（描述方式、不涉及账号，可以明说）。 */
    public void requireAvailable(GuestProvider provider) {
        if (!isAvailable(provider)) {
            throw new LoginMethodUnavailableException("该登录方式未开放，请使用其它方式登录");
        }
    }
}
