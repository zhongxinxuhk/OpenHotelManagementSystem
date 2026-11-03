package cn.sec.hn.openbackupsaas.integration.service;

import cn.sec.hn.openbackupsaas.integration.domain.ExternalTask;
import cn.sec.hn.openbackupsaas.integration.mapper.ExternalTaskMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 外部任务的入队、结果查询与人工处置（S2-03 的"调用结果查询与补偿接口"）。
 *
 * <p>为什么先做服务层接口而不是 REST：管理端的查询/重放页面归 S2-04（受控管理 API/CLI），
 * 那里要一并定义权限、复核与操作留痕。现在先保证<b>服务能力</b>可用且被测试覆盖，
 * 避免在没有权限模型的时候先开一个能改资金状态的 HTTP 口子。
 */
@Service
public class ExternalTaskService {

    private final ExternalTaskMapper mapper;

    public ExternalTaskService(ExternalTaskMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 入队一个外部调用任务。
     *
     * <p>{@code idempotencyKey} 必须由调用方按业务意图生成并复用：
     * 同一笔操作重复入队会命中唯一索引，而不是再多发一次外部请求。
     */
    public UUID enqueue(NewTask task) {
        Objects.requireNonNull(task.tenantId(), "外部任务必须带租户");
        if (task.taskType() == null || task.taskType().isBlank()) {
            throw new IllegalArgumentException("taskType 不能为空");
        }
        if (task.channel() == null || task.channel().isBlank()) {
            throw new IllegalArgumentException("channel 不能为空");
        }
        if (task.idempotencyKey() == null || task.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 不能为空：没有它就无法保证外部系统去重");
        }
        if (task.payload() == null || task.payload().isBlank()) {
            throw new IllegalArgumentException("payload 不能为空");
        }
        int maxAttempts = task.maxAttempts() == null ? 1 : task.maxAttempts();
        return mapper.insertTask(task.tenantId(), task.hotelId(), task.taskType(), task.channel(),
                task.idempotencyKey(), task.payload(), maxAttempts);
    }

    /** 任务当前状态。 */
    public ExternalTask find(UUID taskId) {
        return mapper.findById(taskId);
    }

    /** 任务的全部调用尝试，按尝试序号升序（对账与排查用）。 */
    public List<Map<String, Object>> attempts(UUID taskId) {
        return mapper.findAttempts(taskId);
    }

    /**
     * 人工结案：把 {@code MANUAL_REVIEW} 的任务改判为成功或失败。
     *
     * <p>处置人、原因、结论都写进 {@code last_error}（S2-04 会换成正式的处置记录 + 审计）。
     * 这里只允许从 {@code MANUAL_REVIEW} 出发——机器手上的任务（PENDING/RUNNING）不允许被人工改判，
     * 否则可能与正在进行的调用打架。
     *
     * @return 是否改判成功（状态不符时返回 false，而不是静默成功）
     */
    public boolean resolveManually(UUID taskId, boolean succeeded, String resultRef,
                                   String operator, String reason) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("人工处置必须记录操作者");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("人工处置必须给出原因");
        }
        String status = succeeded ? "SUCCEEDED" : "FAILED";
        String detail = "人工处置：" + operator + " 判定" + (succeeded ? "成功" : "失败")
                + "；原因：" + reason;
        return mapper.settleManualResolution(taskId, status, resultRef, detail) > 0;
    }

    /**
     * 入队参数。
     *
     * @param maxAttempts 与渠道策略取较小值，防止单个任务配出一个渠道不允许的次数
     */
    public record NewTask(UUID tenantId, UUID hotelId, String taskType, String channel,
                          String idempotencyKey, String payload, Integer maxAttempts) {
    }
}
