package cn.sec.hn.openbackupsaas.journal.mapper;

import cn.sec.hn.openbackupsaas.journal.domain.PendingOutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/**
 * Outbox 投递侧的持久化（S2-01）。
 *
 * <p>与 {@link JournalMapper} 分开：写侧（同事务追加事件）与投递侧（跨租户领取、推进投递状态）
 * 的关注点完全不同，混在一个 Mapper 里会让"业务事务里能改投递状态"变成随手可得的事。
 *
 * <p><b>每个方法都是单条语句</b>，因此都不需要显式事务：语义上一条语句就是一个原子步骤，
 * 也不存在"把发布动作包进事务"的诱惑——发布网络调用绝不能持有行锁。
 */
@Mapper
public interface OutboxPublisherMapper {

    /**
     * 领取一批到期事件并打上租约。
     *
     * <p>一条语句同时完成"挑选 + 加锁 + 标记"：子查询用 {@code FOR UPDATE SKIP LOCKED}
     * 跳过已被其他 Worker 持有的行，因此多实例并发领取不会重复，也不会互相等待。
     *
     * <p>幂等性来自租约而非锁：领取后进程崩溃，租约到期后他人可再次领取（允许重发）。
     */
    List<PendingOutboxEvent> claimBatch(@Param("leaseToken") UUID leaseToken,
                                        @Param("leaseSeconds") long leaseSeconds,
                                        @Param("batchSize") int batchSize);

    /**
     * 标记已发布。<b>必须带租约校验</b>：租约过期说明别人可能已经接手，
     * 此时覆盖 {@code published_at} 只会掩盖问题。返回 0 行即"租约已失效或已被发布"。
     */
    int markPublished(@Param("eventId") UUID eventId,
                      @Param("leaseToken") UUID leaseToken);

    /**
     * 记录失败并释放租约，按退避推迟下次可领取时间。
     *
     * <p>{@code backoffSeconds = 0} 表示"立刻可再次领取"，用于 Broker 整体不可用时
     * 中断本轮批次（此时不该让每个事件都背上一次长退避）。
     */
    int markFailed(@Param("eventId") UUID eventId,
                   @Param("leaseToken") UUID leaseToken,
                   @Param("error") String error,
                   @Param("backoffSeconds") long backoffSeconds);

    /** 未发布事件总数，用于积压指标。 */
    long countPending();

    /** 最老未发布事件的年龄（秒）；无待发布事件时返回 {@code null}。 */
    Long oldestPendingSeconds();
}
