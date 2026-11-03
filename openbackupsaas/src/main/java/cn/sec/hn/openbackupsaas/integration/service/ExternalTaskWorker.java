package cn.sec.hn.openbackupsaas.integration.service;

import cn.sec.hn.openbackupsaas.shared.runtime.WorkerRoleCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 外部任务的调度入口（S2-03）。
 *
 * <p>与 Outbox 发布器同构：这一层只做定时与异常兜底，语义都在
 * {@link ExternalTaskExecutor}。因此可以被外部定时器（CronJob、运维手动触发一轮）替换而不丢语义。
 *
 * <p>只在 Worker 角色下装配：接口进程不该承担外部调用——一次对方超时就会占住一个请求线程，
 * 而且接口进程重启时会把正在等待的调用全部打断。
 */
@Component
@Conditional(WorkerRoleCondition.class)
@ConditionalOnProperty(name = "pms.integration.task.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ExternalTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(ExternalTaskWorker.class);

    private final ExternalTaskExecutor executor;

    public ExternalTaskWorker(ExternalTaskExecutor executor) {
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${pms.integration.task.poll-interval:PT10S}",
            initialDelayString = "${pms.integration.task.initial-delay:PT10S}")
    public void poll() {
        try {
            executor.runOnce();
        } catch (RuntimeException ex) {
            // 任务仍在库里，租约到期后可重领；这里只保证调度不中断
            log.error("外部任务轮询失败（任务未丢失，下一轮重新领取）", ex);
        }
    }
}
