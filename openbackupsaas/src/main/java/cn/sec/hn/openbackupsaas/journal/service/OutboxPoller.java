package cn.sec.hn.openbackupsaas.journal.service;

import cn.sec.hn.openbackupsaas.shared.runtime.WorkerRoleCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 投递的调度入口（S2-01）。
 *
 * <p>与 S1-10 的到期扫描同构：这一层只做定时与异常兜底，语义全在
 * {@link OutboxPublisherService}。因此可以无成本地换成外部触发（CronJob、运维手动跑一轮），
 * 不会丢语义。
 *
 * <p><b>只看 Worker 角色</b>：API 进程不跑投递。理由不是洁癖——发布时要<b>等待 Broker 确认</b>，
 * 让接口进程承担这个等待，Broker 一慢就变成接口变慢。
 *
 * <p><b>异常必须在这里吃掉</b>：{@code fixedDelay} 下未捕获异常不会停调度，但会以无上下文的
 * 栈刷日志。这里记一条带结果的 ERROR 后返回，事件仍在库里，下一轮重新领取即是恢复。
 */
@Component
@Conditional(WorkerRoleCondition.class)
@ConditionalOnProperty(name = "pms.outbox.poller.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxPublisherService publisherService;

    public OutboxPoller(OutboxPublisherService publisherService) {
        this.publisherService = publisherService;
    }

    @Scheduled(fixedDelayString = "${pms.outbox.poll-interval:PT5S}",
            initialDelayString = "${pms.outbox.initial-delay:PT5S}")
    public void publishDue() {
        try {
            OutboxPublisherService.PublishReport report = publisherService.publishDue();
            if (report.aborted()) {
                log.error("Outbox 本轮中止：领取 {} 发布 {} 失败 {}；剩余事件已留库，下一轮重试",
                        report.claimed(), report.published(), report.failed());
            }
        } catch (RuntimeException ex) {
            log.error("Outbox 投递轮询失败（事件仍在库中，下一轮重新领取）", ex);
        }
    }
}
