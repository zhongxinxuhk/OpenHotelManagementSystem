package cn.sec.hn.openbackupsaas.platform.service;

import cn.sec.hn.openbackupsaas.guest.domain.GuestLoginPolicy;
import cn.sec.hn.openbackupsaas.guest.domain.GuestProvider;
import cn.sec.hn.openbackupsaas.guest.mapper.GuestLoginPolicyMapper;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 住客登录方式开关（管理员控制台，ADR-019 §2.9）。
 *
 * <p>需求原话："手机号，邮箱这些登录项在管理员控制台去设置"。
 * 本服务只做两件事：列出全部方式（含实现状态），以及启用/停用。
 *
 * <p><b>必须让使用者看到的一句话</b>：开关打开 ≠ 方式可用。可用性 =
 * 实现状态（代码）∧ 开关（本表）。当前只有 USERNAME 已实现——
 * 把 PHONE 的开关打开，客户端与登录接口看到的仍是"未开放"，
 * 界面必须照实显示"配了但没接通"，否则运营会以为打开开关就是在接通道。
 */
@Service
public class PlatformAuthPolicyService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAuthPolicyService.class);

    private final GuestLoginPolicyMapper policyMapper;
    private final Clock clock;

    public PlatformAuthPolicyService(GuestLoginPolicyMapper policyMapper, Clock clock) {
        this.policyMapper = policyMapper;
        this.clock = clock;
    }

    /** 全部登录方式及其状态（实现状态一并返回，界面据此标注"未接通"）。 */
    public List<PolicyView> list() {
        return policyMapper.findAll().stream()
                .map(policy -> toView(GuestProvider.valueOf(policy.provider()), policy))
                .toList();
    }

    /**
     * 启用或停用一个登录方式。
     *
     * @param provider 方式（必须是已知枚举值，拼错直接 400）
     * @param enabled  开关
     * @param operator 操作者的平台用户名（留痕：谁在何时改的开关）
     * @throws NotFoundException 未知方式
     */
    @Transactional
    public PolicyView set(String provider, boolean enabled, String operator) {
        GuestProvider type;
        try {
            type = GuestProvider.valueOf(provider);
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException("未知的登录方式：" + provider);
        }
        // 未实现的方式**允许**打开开关：运维"提前把开关配好、等代码上线当天生效"
        // 是正常操作节奏，409 拒绝会把这条路径堵死。后果由视图里的
        // implemented=false / available=false 如实呈现，界面必须照实标注。
        policyMapper.upsert(type.name(), enabled, operator, OffsetDateTime.now(clock));
        log.warn("guest login policy changed provider={} enabled={} by={}", type, enabled, operator);
        return toView(type, policyMapper.find(type.name()).orElseThrow());
    }

    private static PolicyView toView(GuestProvider type, GuestLoginPolicy policy) {
        boolean implemented = type.isImplemented();
        return new PolicyView(
                policy.provider(),
                implemented,
                policy.enabled(),
                implemented && policy.enabled(),
                policy.updatedAt(),
                policy.updatedBy());
    }

    /**
     * 策略视图。字段语义与住客侧 {@code /guest/auth/methods} 一致，
     * 但多了操作留痕（updatedBy）——控制台要能看到"这个开关是谁动的"。
     */
    public record PolicyView(
            String provider,
            boolean implemented,
            boolean enabled,
            boolean available,
            OffsetDateTime updatedAt,
            String updatedBy
    ) {
    }
}
