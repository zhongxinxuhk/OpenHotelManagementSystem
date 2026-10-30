package cn.sec.hn.openbackupsaas.integration.mapper;

import cn.sec.hn.openbackupsaas.integration.domain.ErrorClass;
import cn.sec.hn.openbackupsaas.integration.domain.ExternalTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 外部任务的数据访问（S2-03）。SQL 见 {@code resources/mapper/integration/ExternalTaskMapper.xml}。
 *
 * <p>每个方法都是<b>单条语句</b>：执行器在事务外调用外部系统，若把"读任务—调用—写状态"
 * 包进一个事务，网络等待期间就会一直持有行锁，且失败回滚会把尝试记录一起丢掉。
 *
 * <p>所有结算方法都带 {@code lease_token} 条件：租约过期说明这条已换人，
 * 此时覆盖状态会掩盖"到底有没有执行"——与 Outbox 的标记同理。
 */
@Mapper
public interface ExternalTaskMapper {

    /** 入队并返回任务 id。同租户同类型同幂等键的未结任务唯一，重复入队会抛唯一键冲突。 */
    UUID insertTask(@Param("tenantId") UUID tenantId,
                   @Param("hotelId") UUID hotelId,
                   @Param("taskType") String taskType,
                   @Param("channel") String channel,
                   @Param("idempotencyKey") String idempotencyKey,
                   @Param("payload") String payload,
                   @Param("maxAttempts") int maxAttempts);

    /**
     * 领取到期任务并打租约。
     *
     * <p>领取条件包含<b>两条路径</b>：
     * <ol>
     *   <li>待执行且退避已到期；</li>
     *   <li>执行中但租约已过期——执行器崩溃后重新领取靠的就是这条。
     *       只认 PENDING 会让崩溃过的任务永远停在 RUNNING。</li>
     * </ol>
     */
    List<ExternalTask> claimBatch(@Param("leaseToken") UUID leaseToken,
                                  @Param("leaseSeconds") long leaseSeconds,
                                  @Param("batchSize") int batchSize);

    /** 记一次真实调用尝试。attempt_no 与任务上的 attempts 同源，用 GREATEST 防止并发回写倒退。 */
    int insertAttempt(@Param("taskId") UUID taskId,
                      @Param("attemptNo") int attemptNo,
                      @Param("startedAt") OffsetDateTime startedAt,
                      @Param("finishedAt") OffsetDateTime finishedAt,
                      @Param("outcome") String outcome,
                      @Param("errorClass") ErrorClass errorClass,
                      @Param("detail") String detail,
                      @Param("resultRef") String resultRef);

    int markAttempts(@Param("taskId") UUID taskId,
                     @Param("attemptNo") int attemptNo,
                     @Param("errorClass") ErrorClass errorClass,
                     @Param("detail") String detail);

    int settleSucceeded(@Param("taskId") UUID taskId,
                        @Param("leaseToken") UUID leaseToken,
                        @Param("resultRef") String resultRef);

    int settleRetry(@Param("taskId") UUID taskId,
                    @Param("leaseToken") UUID leaseToken,
                    @Param("errorClass") ErrorClass errorClass,
                    @Param("detail") String detail,
                    @Param("nextAttemptAt") OffsetDateTime nextAttemptAt);

    /** 永久失败：不再重试。 */
    int settleFailed(@Param("taskId") UUID taskId,
                     @Param("leaseToken") UUID leaseToken,
                     @Param("errorClass") ErrorClass errorClass,
                     @Param("detail") String detail);

    /** 转人工：结果未知且对方不具备查询能力，任何自动处置都可能造成重复或丢失。 */
    int settleManual(@Param("taskId") UUID taskId,
                     @Param("leaseToken") UUID leaseToken,
                     @Param("errorClass") ErrorClass errorClass,
                     @Param("detail") String detail);

    /** 次数耗尽。与永久失败分开：这类任务人工重放通常有效。 */
    int settleDead(@Param("taskId") UUID taskId,
                   @Param("leaseToken") UUID leaseToken,
                   @Param("errorClass") ErrorClass errorClass,
                   @Param("detail") String detail);

    /**
     * 释放租约但不计入尝试：限流/熔断跳过的轮次不该消耗重试预算，
     * 否则"对方让我慢一点"会变成"我没机会再试"。
     */
    int release(@Param("taskId") UUID taskId,
                @Param("leaseToken") UUID leaseToken,
                @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
                @Param("reason") String reason);

    ExternalTask findById(@Param("taskId") UUID taskId);

    /**
     * 人工处置：把 MANUAL_REVIEW 的任务结案。
     *
     * <p>不加租约条件——人工处置的前提就是"机器不再碰它"；但仍要求当前状态是 MANUAL_REVIEW，
     * 避免把一条已经被重放成功的任务改回失败。处置人、原因、前后状态记在 detail 里（S2-04 补审计）。
     */
    int settleManualResolution(@Param("taskId") UUID taskId,
                               @Param("status") String status,
                               @Param("resultRef") String resultRef,
                               @Param("detail") String detail);

    /** 该任务的尝试台账，按尝试序号升序。 */
    List<java.util.Map<String, Object>> findAttempts(@Param("taskId") UUID taskId);

    List<ExternalTask> findByStatus(@Param("status") String status, @Param("limit") int limit);
}
