package cn.sec.hn.openbackupsaas.guest.web;

import cn.sec.hn.openbackupsaas.guest.domain.GuestVerificationStatus;
import cn.sec.hn.openbackupsaas.guest.security.GuestPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 住客自助查询（ADR-019 §2.3）。
 *
 * <p>本轮只有 {@code /me}——它的作用是让"住客链是否真的独立"可被验证：
 * 住客令牌打这个接口通，打商家接口必须 401。
 *
 * <p><b>不返回主身份 id</b>：ADR-018 定的是"客户端不感知主 id"。住客侧接口
 * 一律不把内部 UUID 带出去，订单等业务对象用不可猜测引用，不用主键。
 */
@RestController
@RequestMapping("/api/v1/guest")
@Tag(name = "住客自助", description = "住客平面接口（独立会话，与商家/平台平面隔离）")
public class GuestSelfController {

    /**
     * 当前住客的身份与实名状态。
     *
     * <p>只放"客户端需要据此决定界面"的字段：称呼、实名状态、以及一个明确的
     * {@code realNameRequired} 开关。让客户端自己由 status 推导"能不能下单"，
     * 迟早会出现"客户端允许、服务端拒绝"或反过来的错位。
     *
     * <p>{@code realNameRequired} 的含义是"**还需要**去做实名"，即
     * {@code !status.satisfiesRealNameRequirement()}。这个字段第一次实现时被传成了
     * {@code satisfiesRealNameRequirement()}——名字与值正好相反，接口照样返回 200，
     * 客户端会把"未实名"当成"已验证"放行。是接口测试断言抓到的。
     */
    @GetMapping("/me")
    @Operation(operationId = "getGuestMe")
    public GuestMeView me(@AuthenticationPrincipal GuestPrincipal principal) {
        GuestVerificationStatus status = principal.verificationStatus();
        return new GuestMeView(
                principal.displayName(),
                status.name(),
                !status.satisfiesRealNameRequirement(),
                principal.sessionExpiresAt());
    }

    /**
     * 住客自身的可读状态。**不含主身份 id**（ADR-018：客户端不感知内部标识）。
     *
     * @param realNameRequired 还需要完成实名（未通过时为 true）
     */
    public record GuestMeView(
            String displayName,
            String verificationStatus,
            boolean realNameRequired,
            java.time.OffsetDateTime sessionExpiresAt
    ) {
    }
}
