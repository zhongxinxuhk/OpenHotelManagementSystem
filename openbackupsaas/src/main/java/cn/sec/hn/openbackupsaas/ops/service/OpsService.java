package cn.sec.hn.openbackupsaas.ops.service;

import cn.sec.hn.openbackupsaas.inbox.service.InboxMessageDispatcher;
import cn.sec.hn.openbackupsaas.integration.domain.ExternalTask;
import cn.sec.hn.openbackupsaas.integration.mapper.ExternalTaskMapper;
import cn.sec.hn.openbackupsaas.ops.domain.DeadLetter;
import cn.sec.hn.openbackupsaas.ops.domain.DeadLetterStatus;
import cn.sec.hn.openbackupsaas.ops.domain.OpsIntervention;
import cn.sec.hn.openbackupsaas.ops.mapper.OpsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 运维处置：死信重放/忽略、外部任务重放与人工结案（S2-04）。
 *
 * <h2>为什么每次处置都要在同一事务里写留痕</h2>
 * 只改状态不留痕，事后无法追责；只留痕不改状态，台账就是在说谎。
 * 所以这两个动作必须同生共死——这也是本类方法上 {@code @Transactional} 的唯一理由。
 *
 * <h2>重放为什么不新造一条任务</h2>
 * 重放走的是<b>原来的那条</b>：保留原幂等键（外部系统按它去重）、保留尝试历史、
 * 只把状态拉回 PENDING 并延长次数上限。新造一条等于换了个身份，对方会当成另一笔业务再执行一次。
 *
 * <h2>高风险处置的复核</h2>
 * 资金/库存类任务（任务类型由 {@code pms.ops.high-risk-task-types} 指定）重放时要求
 * <b>第二位操作者</b>，且不能与动手的人相同。单人可完成的复核不是复核。
 */
@Service
public class OpsService {

    private static final Logger log = LoggerFactory.getLogger(OpsService.class);

    private final OpsMapper opsMapper;
    private final ExternalTaskMapper taskMapper;
    private final InboxMessageDispatcher dispatcher;
    private final Set<String> highRiskTaskTypes;
    private final int replayExtraAttempts;

    public OpsService(OpsMapper opsMapper,
                      ExternalTaskMapper taskMapper,
                      InboxMessageDispatcher dispatcher,
                      @Value("${pms.ops.high-risk-task-types:}") String highRiskTypes,
                      @Value("${pms.ops.replay-extra-attempts:3}") int replayExtraAttempts) {
        if (replayExtraAttempts < 1) {
            throw new IllegalArgumentException("pms.ops.replay-extra-attempts 至少为 1，收到 " + replayExtraAttempts);
        }
        this.opsMapper = opsMapper;
        this.taskMapper = taskMapper;
        this.dispatcher = dispatcher;
        this.highRiskTaskTypes = Arrays.stream(highRiskTypes.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.replayExtraAttempts = replayExtraAttempts;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public PendingSummary pending() {
        long deadLetters = opsMapper.countPendingDeadLetters();
        long stuckTasks = opsMapper.countExternalTasksByStatus(List.of("DEAD", "MANUAL_REVIEW"));
        long parkedEvents = opsMapper.countParkedInboxEvents();
        return new PendingSummary(deadLetters, stuckTasks, parkedEvents);
    }

    public List<DeadLetter> pendingDeadLetters(int limit) {
        return opsMapper.findPendingDeadLetters(requirePositive(limit));
    }

    public List<ExternalTask> tasksByStatus(String status, int limit) {
        return taskMapper.findByStatus(status.toUpperCase(Locale.ROOT), requirePositive(limit));
    }

    public List<OpsIntervention> recentInterventions(int limit) {
        return opsMapper.findRecentInterventions(requirePositive(limit));
    }

    // ------------------------------------------------------------------
    // 死信处置
    // ------------------------------------------------------------------

    /**
     * 重放一条死信：用它<b>原始的报文</b>重新走一遍解析与分发。
     *
     * <p>重放不落新死信（{@code recordDeadLetter=false}）：原记录还在，
     * 再落一条只会让待处置队列里出现两条看起来相同的项。
     */
    @Transactional
    public ReplayResult replayDeadLetter(UUID deadLetterId, UUID operatorId, String operatorName, String reason) {
        requireReason(reason);
        DeadLetter letter = opsMapper.findDeadLetter(deadLetterId);
        if (letter == null) {
            return ReplayResult.notFound();
        }
        if (letter.status() != DeadLetterStatus.PENDING) {
            return new ReplayResult(false, "死信当前状态为 " + letter.status() + "，只能重放 PENDING");
        }

        InboxMessageDispatcher.DispatchResult result = dispatcher.dispatch(letter.messageBody(), false);
        if (!result.processed()) {
            // 仍然解析不了：保持 PENDING（以后还可能修好消费者/补字段重放），并留下这次尝试
            recordIntervention(letter.tenantId(), OpsIntervention.TargetType.DEAD_LETTER, deadLetterId,
                    OpsIntervention.Action.REPLAY, operatorId, operatorName, null, reason,
                    "PENDING", "PENDING（重放仍失败：" + result.detail() + "）");
            return new ReplayResult(false, "重放失败：" + result.detail());
        }

        opsMapper.settleDeadLetter(deadLetterId, DeadLetterStatus.REPLAYED.name(), null);
        recordIntervention(letter.tenantId(), OpsIntervention.TargetType.DEAD_LETTER, deadLetterId,
                OpsIntervention.Action.REPLAY, operatorId, operatorName, null, reason,
                "PENDING", "REPLAYED（应用 " + result.consumersApplied() + " 个消费者）");
        return new ReplayResult(true, "已重放");
    }

    /** 忽略一条死信：确认不必处理。必须给原因——半年后没人能替这个决定作证。 */
    @Transactional
    public ReplayResult dismissDeadLetter(UUID deadLetterId, UUID operatorId, String operatorName, String reason) {
        requireReason(reason);
        DeadLetter letter = opsMapper.findDeadLetter(deadLetterId);
        if (letter == null) {
            return ReplayResult.notFound();
        }
        int updated = opsMapper.settleDeadLetter(deadLetterId, DeadLetterStatus.DISMISSED.name(),
                "已忽略：" + reason);
        if (updated == 0) {
            return new ReplayResult(false, "死信当前状态为 " + letter.status() + "，只能忽略 PENDING");
        }
        recordIntervention(letter.tenantId(), OpsIntervention.TargetType.DEAD_LETTER, deadLetterId,
                OpsIntervention.Action.DISMISS, operatorId, operatorName, null, reason,
                "PENDING", "DISMISSED");
        return new ReplayResult(true, "已忽略");
    }

    // ------------------------------------------------------------------
    // 外部任务处置
    // ------------------------------------------------------------------

    /**
     * 重放一个外部任务。
     *
     * <p>只允许从 {@code DEAD} / {@code MANUAL_REVIEW} 出发：{@code SUCCEEDED} 重放会把已完成的
     * 再发一次，{@code RUNNING} 重放会与正在执行的实例打架。
     */
    @Transactional
    public ReplayResult replayExternalTask(UUID taskId, UUID tenantId, UUID operatorId, String operatorName,
                                           String reviewer, String reason) {
        requireReason(reason);
        ExternalTask task = taskMapper.findById(taskId);
        if (task == null || (tenantId != null && !tenantId.equals(task.tenantId()))) {
            // 跨租户一律按"不存在"处理，不透露他租户是否真的有这条任务
            return ReplayResult.notFound();
        }
        if (isHighRisk(task.taskType())) {
            if (reviewer == null || reviewer.isBlank()) {
                return new ReplayResult(false, "高风险任务（" + task.taskType() + "）重放需要复核人");
            }
            if (reviewer.trim().equalsIgnoreCase(operatorName)) {
                return new ReplayResult(false, "复核人不能是操作者本人");
            }
        }

        int updated = opsMapper.replayExternalTask(taskId, replayExtraAttempts, "人工重放：" + reason);
        if (updated == 0) {
            return new ReplayResult(false, "任务当前状态为 " + task.status() + "，只能重放 DEAD/MANUAL_REVIEW");
        }
        recordIntervention(task.tenantId(), OpsIntervention.TargetType.EXTERNAL_TASK, taskId,
                OpsIntervention.Action.REPLAY, operatorId, operatorName, reviewer, reason,
                describe(task), "PENDING（次数上限 +" + replayExtraAttempts + "）");
        log.info("任务 {} 被 {} 重放（复核人 {}）", taskId, operatorName, reviewer);
        return new ReplayResult(true, "已重放");
    }

    /** 人工结案：把 MANUAL_REVIEW 判定为成功或失败。 */
    @Transactional
    public ReplayResult resolveExternalTask(UUID taskId, UUID tenantId, boolean succeeded, String resultRef,
                                            UUID operatorId, String operatorName, String reason) {
        requireReason(reason);
        ExternalTask task = taskMapper.findById(taskId);
        if (task == null || (tenantId != null && !tenantId.equals(task.tenantId()))) {
            return ReplayResult.notFound();
        }
        int updated = taskMapper.settleManualResolution(taskId,
                succeeded ? "SUCCEEDED" : "FAILED", resultRef,
                "人工处置：" + operatorName + " 判定" + (succeeded ? "成功" : "失败") + "；原因：" + reason);
        if (updated == 0) {
            return new ReplayResult(false, "任务当前状态为 " + task.status() + "，只能人工处置 MANUAL_REVIEW");
        }
        recordIntervention(task.tenantId(), OpsIntervention.TargetType.EXTERNAL_TASK, taskId,
                succeeded ? OpsIntervention.Action.RESOLVE_SUCCEEDED : OpsIntervention.Action.RESOLVE_FAILED,
                operatorId, operatorName, null, reason, describe(task),
                succeeded ? "SUCCEEDED" : "FAILED");
        return new ReplayResult(true, "已结案");
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void recordIntervention(UUID tenantId, OpsIntervention.TargetType targetType, UUID targetId,
                                    OpsIntervention.Action action, UUID operatorId, String operatorName,
                                    String reviewer, String reason, String before, String after) {
        opsMapper.insertIntervention(new OpsIntervention(
                UUID.randomUUID(), tenantId, targetType, targetId, action,
                operatorId, operatorName, reviewer, reason, before, after,
                java.time.OffsetDateTime.now()));
    }

    private boolean isHighRisk(String taskType) {
        return taskType != null && highRiskTaskTypes.contains(taskType.toLowerCase(Locale.ROOT));
    }

    private static String describe(ExternalTask task) {
        return task.status() + "（尝试 " + task.attemptsOrZero() + "/" + task.maxAttemptsOrDefault() + "）";
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("人工处置必须给出原因");
        }
    }

    private static int requirePositive(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit 必须在 1—200 之间");
        }
        return limit;
    }

    /**
     * 待处置概览。
     *
     * @param deadLetters    待处理死信
     * @param stuckTasks     DEAD / MANUAL_REVIEW 的外部任务
     * @param parkedInbox    版本缺口挂起的事件
     */
    public record PendingSummary(long deadLetters, long stuckTasks, long parkedInbox) {
    }

    /**
     * 处置结果。
     *
     * @param applied 是否真的生效
     * @param detail  说明（未生效时必须能说清为什么）
     */
    public record ReplayResult(boolean applied, String detail) {

        /** 「不存在」要用独立措辞区分：接口层据此返回 404 而不是 409，避免泄露对象存在性。 */
        public static final String NOT_FOUND_DETAIL = "目标不存在";

        public static ReplayResult notFound() {
            return new ReplayResult(false, NOT_FOUND_DETAIL);
        }

        public boolean isNotFound() {
            return NOT_FOUND_DETAIL.equals(detail);
        }
    }
}
