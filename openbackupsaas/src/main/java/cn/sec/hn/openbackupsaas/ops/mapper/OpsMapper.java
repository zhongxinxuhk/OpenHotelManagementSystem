package cn.sec.hn.openbackupsaas.ops.mapper;

import cn.sec.hn.openbackupsaas.ops.domain.DeadLetter;
import cn.sec.hn.openbackupsaas.ops.domain.OpsIntervention;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/**
 * 死信、处置留痕与待处置统计的数据访问（S2-04）。
 *
 * <p>重放外部任务也放在这里：它跨 `external_task` 与 `ops_intervention` 两张表，
 * 属"运维处置"这个动作的一部分，拆到两个 Mapper 只会让调用方忘记写留痕。
 */
@Mapper
public interface OpsMapper {

    UUID insertDeadLetter(@Param("tenantId") UUID tenantId,
                          @Param("source") String source,
                          @Param("messageBody") String messageBody,
                          @Param("error") String error);

    DeadLetter findDeadLetter(@Param("deadLetterId") UUID deadLetterId);

    List<DeadLetter> findPendingDeadLetters(@Param("limit") int limit);

    /** 结案死信。只允许从 PENDING 出发，防止重复重放把一条报文处理两次。 */
    int settleDeadLetter(@Param("deadLetterId") UUID deadLetterId,
                         @Param("status") String status,
                         @Param("detail") String detail);

    /**
     * 重放外部任务：把 DEAD / MANUAL_REVIEW 拉回待执行。
     *
     * <p><b>不重置 attempts，而是把 max_attempts 提高</b>：重置计数会让尝试台账的
     * {@code attempt_no} 与历史行冲突（主键是 (task_id, attempt_no)），
     * 而且"被重放过几次"就再也看不出来了。提高上限既保留历史，又给出新的重试预算。
     *
     * <p>幂等键<b>原样保留</b>：外部系统按它去重，换键等于允许对方再执行一次。
     */
    int replayExternalTask(@Param("taskId") UUID taskId,
                           @Param("extraAttempts") int extraAttempts,
                           @Param("reason") String reason);

    int insertIntervention(@Param("i") OpsIntervention intervention);

    List<OpsIntervention> findRecentInterventions(@Param("limit") int limit);

    long countExternalTasksByStatus(@Param("statuses") List<String> statuses);

    long countPendingDeadLetters();

    long countParkedInboxEvents();

    /** 高风险任务类型判定用的任务元信息（只取类型与渠道，避免把整行读出来）。 */
    java.util.Map<String, Object> findExternalTaskMeta(@Param("taskId") UUID taskId);
}
