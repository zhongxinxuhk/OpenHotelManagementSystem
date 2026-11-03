package cn.sec.hn.openbackupsaas.reservation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 锁房超时扫描的调度入口（S1-10）。
 *
 * <p><b>这一层只做三件事</b>：定时、传批大小、不要让异常打断调度。业务全在
 * {@link ReservationExpiryService} 里，所以调度器可以被无成本地替换成外部定时器
 * （K8s CronJob、消息延迟投递），而不会丢语义。
 *
 * <p><b>为什么异常必须在这里吃掉：</b>{@code fixedDelay} 模式下单次失败不会停止后续调度，
 * 但未捕获的异常会以 ERROR 栈的形式反复刷日志且没有业务上下文。这里记一条带批大小的
 * ERROR 后返回，下一轮重新扫描——到期条件仍然成立，重试就是正确的恢复方式。
 *
 * <p><b>多实例：</b>每个实例都会扫描，靠 {@code FOR UPDATE SKIP LOCKED} 与条件更新保证
 * 同一单只被释放一次，因此不需要分布式锁。
 */
@Component
@ConditionalOnProperty(name = "pms.reservation.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryScheduler.class);

    private final ReservationExpiryService expiryService;
    private final int batchSize;

    public ReservationExpiryScheduler(ReservationExpiryService expiryService,
                                     @Value("${pms.reservation.expiry.batch-size:50}") int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("pms.reservation.expiry.batch-size 必须为正数，收到 " + batchSize);
        }
        this.expiryService = expiryService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${pms.reservation.expiry.scan-interval:PT30S}",
            initialDelayString = "${pms.reservation.expiry.initial-delay:PT10S}")
    public void scanDueHolds() {
        try {
            expiryService.expireDueHolds(batchSize);
        } catch (RuntimeException ex) {
            // 整批已回滚，没有任何半完成状态被提交；下一轮会重新领到这些行
            log.error("到期承接扫描失败（批大小 {}），本轮已整体回滚，等待下一轮重试", batchSize, ex);
        }
    }
}
