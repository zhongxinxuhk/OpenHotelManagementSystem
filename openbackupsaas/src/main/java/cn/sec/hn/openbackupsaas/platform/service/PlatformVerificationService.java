package cn.sec.hn.openbackupsaas.platform.service;

import cn.sec.hn.openbackupsaas.guest.domain.GuestVerificationReview;
import cn.sec.hn.openbackupsaas.guest.domain.GuestVerificationStatus;
import cn.sec.hn.openbackupsaas.guest.domain.PendingVerification;
import cn.sec.hn.openbackupsaas.guest.mapper.GuestMapper;
import cn.sec.hn.openbackupsaas.guest.service.GuestVerificationDecisionService;
import cn.sec.hn.openbackupsaas.platform.security.PlatformPrincipal;
import cn.sec.hn.openbackupsaas.shared.web.PageView;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 实名人工审核（ADR-019 §2.5）。
 *
 * <p>这一层只做"平台视角的编排"：取队列、把审核员的身份补进决定、返回留痕。
 * 真正的状态变迁与并发控制在 {@link GuestVerificationDecisionService}——
 * 那是人工与（将来的）权威渠道共用的唯一入口，不能在这里重写一遍。
 *
 * <p><b>为什么审核员必须是平台身份而不是商家员工</b>：实名数据在住客平面，
 * 不属于任何租户。让酒店前台能读客人身份证，是最典型的一类内部数据滥用；
 * 而"某家店审自己的客人"还会带来竞争性滥用（用客人信息做营销、挖客源）。
 * 平台侧集中审核，配上留痕，才有可追责的对象。
 */
@Service
public class PlatformVerificationService {

    private final GuestMapper guestMapper;
    private final GuestVerificationDecisionService decisionService;

    public PlatformVerificationService(GuestMapper guestMapper,
                                       GuestVerificationDecisionService decisionService) {
        this.guestMapper = guestMapper;
        this.decisionService = decisionService;
    }

    /**
     * 待审核队列。
     *
     * <p>只返回掩码，不解密、不带 accountId 之外的任何标识。审核员在这里的工作是
     * "这张证件照清晰吗、姓名与证件一致吗"，不需要完整号码。
     */
    public PageView<PendingVerification> pending(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.clamp(size, 1, 100);
        long total = guestMapper.countPendingVerifications();
        List<PendingVerification> items = total == 0
                ? List.of()
                : guestMapper.findPendingVerifications(safeSize, (safePage - 1) * safeSize);
        return PageView.of(items, safePage, safeSize, total);
    }

    /**
     * 执行人工审核。
     *
     * <p>审核员信息从**会话**取，不从请求体取：请求体能伪造"是谁审的"，
     * 而留痕的价值全在"这一条是谁做的"上。
     */
    public GuestVerificationReview review(java.util.UUID accountId,
                                          GuestVerificationStatus decision,
                                          String reason,
                                          PlatformPrincipal reviewer) {
        return decisionService.decide(accountId, decision,
                GuestVerificationReview.SOURCE_MANUAL,
                reason, null,
                reviewer.userId(), reviewer.displayName());
    }

    /** 某住客的实名决定历史，含掩码快照。 */
    public List<GuestVerificationReview> history(java.util.UUID accountId) {
        return decisionService.reviews(accountId);
    }
}
