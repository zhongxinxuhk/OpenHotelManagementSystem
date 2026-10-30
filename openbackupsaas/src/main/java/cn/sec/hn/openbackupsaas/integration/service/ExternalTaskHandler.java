package cn.sec.hn.openbackupsaas.integration.service;

import cn.sec.hn.openbackupsaas.integration.domain.ExternalTask;
import cn.sec.hn.openbackupsaas.integration.domain.QueryOutcome;
import cn.sec.hn.openbackupsaas.integration.domain.TaskOutcome;

/**
 * 外部调用处理器（S2-03）。
 *
 * <p>执行器负责状态机、租约、退避与限流；处理器只负责"怎么调这一家"。
 * 拆开的原因：状态机要对所有渠道一致，而每家 OTA/支付的协议各不相同，
 * 混在一个类里会导致每接一家都要改核心逻辑。
 *
 * <h2>{@link #supportsQuery()} 决定的东西比它看起来重要</h2>
 * 返回 {@code false} 时，一旦出现"结果未知"，执行器会直接转人工——
 * 因为它既不能确认成功（可能没到），也不能安全重发（可能已生效）。
 * <b>不要为了让流程自动化而谎报支持查询</b>：那会把"重复扣款"变成自动化。
 */
public interface ExternalTaskHandler {

    /** 任务类型，与入队时的 {@code task_type} 对应。 */
    String taskType();

    /**
     * 该渠道是否支持"按幂等键查询结果"。
     *
     * <p>只有真的能查（对方提供查询接口或用幂等键回查）才返回 true。
     */
    default boolean supportsQuery() {
        return false;
    }

    /**
     * 调用外部系统。
     *
     * <p>实现方负责把异常翻译成 {@link TaskOutcome} 的语义：
     * 超时必须返回 {@code unknown} 而不是抛异常——"超时"与"明确失败"是两种处置。
     * 未捕获的异常由执行器按 {@code unknown} 兜底（宁可转人工，不可重复执行）。
     */
    TaskOutcome execute(ExternalTask task) throws Exception;

    /**
     * 查询上一次调用的真实结果。仅在 {@link #supportsQuery()} 为 true 时被调用。
     */
    default QueryOutcome query(ExternalTask task) {
        return QueryOutcome.unknown("该处理器未实现查询");
    }
}
