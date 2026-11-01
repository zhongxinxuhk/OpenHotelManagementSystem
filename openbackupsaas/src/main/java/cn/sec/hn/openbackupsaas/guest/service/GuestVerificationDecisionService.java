package cn.sec.hn.openbackupsaas.guest.service;

import cn.sec.hn.openbackupsaas.guest.domain.GuestVerification;
import cn.sec.hn.openbackupsaas.guest.domain.GuestVerificationReview;
import cn.sec.hn.openbackupsaas.guest.domain.GuestVerificationStatus;
import cn.sec.hn.openbackupsaas.guest.mapper.GuestMapper;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 实名决定的**唯一入口**（ADR-019 §2.5）。
 *
 * <p>人工审核与（将来的）权威渠道都调这一个方法，区别只在 {@code source}：
 * 人工传 {@link GuestVerificationReview#SOURCE_MANUAL}，权威渠道传
 * {@code AUTHORITY:<channelCode>}。状态机、并发控制、留痕只有一份实现——
 * 两条通道各写一遍的话，它们的"通过"迟早会有细微差别，而这种差别在审计里看不出来。
 *
 * <p><b>本类刻意不依赖任何渠道配置/熔断状态</b>（构造参数里没有 ChannelMapper）。
 * 这就是"权威接口熔断时人工审核照常"的实现保证：不是靠一个 if 判断跳过渠道，
 * 而是人工路径**结构上没有**读渠道的能力。想让熔断影响人工审核，得先改本类的构造器。
 */
@Service
public class GuestVerificationDecisionService {

    private static final Logger log = LoggerFactory.getLogger(GuestVerificationDecisionService.class);

    private final GuestMapper guestMapper;
    private final Clock clock;

    public GuestVerificationDecisionService(GuestMapper guestMapper, Clock clock) {
        this.guestMapper = guestMapper;
        this.clock = clock;
    }

    /**
     * 落地一个实名决定并留痕。
     *
     * @param accountId 住客主身份
     * @param decision  只能是 VERIFIED 或 REJECTED
     * @param source    MANUAL 或 AUTHORITY:&lt;code&gt;
     * @param reason    人工决定必填（库层也有 CHECK 兜底）
     * @param detail    渠道返回码等补充信息，可为空
     * @param reviewerId 人工执行者；权威渠道决定传 null
     * @param reviewerName 人工执行者显示名；权威渠道传 null
     * @throws NotFoundException 没有实名档案
     * @throws ConflictException 该申请已被他人处理（不是 PENDING 了）
     */
    @Transactional
    public GuestVerificationReview decide(UUID accountId,
                                          GuestVerificationStatus decision,
                                          String source,
                                          String reason,
                                          String detail,
                                          UUID reviewerId,
                                          String reviewerName) {
        if (decision != GuestVerificationStatus.VERIFIED
                && decision != GuestVerificationStatus.REJECTED) {
            // 只有两种"结论"。PENDING 是"还没结论"，UNVERIFIED 是"没提交过"，
            // 让它们从这里进来会写出"把申请改成待审核"这种没有意义的状态变迁。
            throw new IllegalArgumentException("实名决定只能是 VERIFIED 或 REJECTED，收到 " + decision);
        }

        GuestVerification current = guestMapper.findVerification(accountId)
                .orElseThrow(() -> new NotFoundException("该住客没有实名申请：" + accountId));

        OffsetDateTime now = OffsetDateTime.now(clock);
        // REJECTED 不记 verified_at：那一列的含义是"何时验证通过"，
        // 驳回时写时间会让"按 verified_at 非空统计已实名"这种查询把驳回的也算进来。
        OffsetDateTime verifiedAt = decision == GuestVerificationStatus.VERIFIED ? now : null;

        int updated = guestMapper.applyVerificationDecision(accountId, decision.name(), verifiedAt, now);
        if (updated == 0) {
            // 并发防线：另一个审核员刚处理过同一条申请。
            // 这里必须是 409 而不是"静默成功"——两条互相矛盾的决定都写着成功，
            // 事后谁也无法判断最终结论是怎么来的。
            throw new ConflictException("该实名申请已被处理，请刷新后查看最新状态");
        }

        GuestVerificationReview review = new GuestVerificationReview(
                UUID.randomUUID(), accountId,
                // 掩码快照：档案之后可能被重新提交覆盖，留痕要能独立说清"当时审的是哪一份"
                current.idNumberMask(), current.realNameMask(),
                decision, source, reason, detail, reviewerId, reviewerName, now);
        guestMapper.insertReview(review);

        log.info("guest verification decided accountId={} decision={} source={} reviewer={}",
                accountId, decision, source, reviewerId);
        return review;
    }

    /** 某住客的实名决定历史（新的在前）。 */
    public List<GuestVerificationReview> reviews(UUID accountId) {
        return guestMapper.findReviews(accountId);
    }
}
