package cn.sec.hn.openbackupsaas.platform.web;

import cn.sec.hn.openbackupsaas.guest.domain.GuestVerificationReview;
import cn.sec.hn.openbackupsaas.guest.domain.GuestVerificationStatus;
import cn.sec.hn.openbackupsaas.guest.domain.PendingVerification;
import cn.sec.hn.openbackupsaas.platform.security.PlatformPrincipal;
import cn.sec.hn.openbackupsaas.platform.service.PlatformVerificationService;
import cn.sec.hn.openbackupsaas.shared.web.PageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 实名人工审核（ADR-019 §2.5）。
 *
 * <p>读与写分成两个动作码：{@code guest.verification.read} 只能看队列，
 * {@code guest.verification.review} 才能下结论。现实中这两个权限确实会分给不同的人
 * （一线只做初筛、主管才有权通过），合成一个动作码就没法表达了。
 */
@RestController
@RequestMapping("/api/v1/platform/guest-verifications")
@Tag(name = "实名人工审核", description = "平台侧审核住客实名资料（只给掩码，决定必填理由）")
public class PlatformVerificationController {

    private final PlatformVerificationService verificationService;

    public PlatformVerificationController(PlatformVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    /**
     * 待审核队列。
     *
     * <p>返回里**只有掩码**（前 6 位 + 后 4 位）。完整证件号不在这里出现，
     * 也不提供"点开看完整号码"的入口——那需要独立的受审计取证流程。
     */
    @GetMapping
    @PreAuthorize("hasAuthority('guest.verification.read')")
    @Operation(operationId = "listGuestVerifications")
    public PageView<PendingVerification> pending(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return verificationService.pending(page, size);
    }

    /**
     * 通过或驳回。
     *
     * <p>理由必填（库层也有 CHECK）。没有理由的"通过"事后无法复核，
     * 等于把审核退化成一次点击。
     */
    @PostMapping("/{accountId}/decision")
    @PreAuthorize("hasAuthority('guest.verification.review')")
    @Operation(operationId = "reviewGuestVerification")
    public ReviewView decide(@PathVariable UUID accountId,
                             @Valid @RequestBody ReviewDecisionRequest request,
                             @AuthenticationPrincipal PlatformPrincipal reviewer) {
        GuestVerificationReview review = verificationService.review(
                accountId, request.decision(), request.reason().trim(), reviewer);
        return ReviewView.of(review);
    }

    /** 某住客的实名决定历史（含当时的掩码快照）。 */
    @GetMapping("/{accountId}/history")
    @PreAuthorize("hasAuthority('guest.verification.read')")
    @Operation(operationId = "listGuestVerificationHistory")
    public List<ReviewView> history(@PathVariable UUID accountId) {
        return verificationService.history(accountId).stream().map(ReviewView::of).toList();
    }

    public record ReviewDecisionRequest(
            @NotNull(message = "决定不能为空") GuestVerificationStatus decision,
            @NotBlank(message = "必须填写审核理由；没有理由的审核事后无法复核") String reason
    ) {
    }

    /** 留痕视图。审核员姓名一并返回，便于同屏确认"这条是谁做的"。 */
    public record ReviewView(
            UUID accountId,
            String idNumberMask,
            String realNameMask,
            String decision,
            String source,
            String reason,
            String reviewerName,
            java.time.OffsetDateTime decidedAt
    ) {

        static ReviewView of(GuestVerificationReview review) {
            return new ReviewView(review.accountId(), review.idNumberMask(),
                    review.realNameMask(), review.decision().name(), review.source(),
                    review.reason(), review.reviewerName(), review.decidedAt());
        }
    }
}
