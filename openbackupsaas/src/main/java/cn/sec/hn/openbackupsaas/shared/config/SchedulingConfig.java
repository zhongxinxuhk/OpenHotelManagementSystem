package cn.sec.hn.openbackupsaas.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务装配（S1-10 引入，S2-01 调整）。
 *
 * <p><b>为什么不再在这里挂条件：</b>初期只有一个定时任务（锁房超时扫描），开关放在这里还能理解为
 * "这个功能开不开"。现在有两个互不相干的定时任务（超时扫描、Outbox 投递），各有自己的开关；
 * 再在这里挂任何一个开关的条件，就会出现"关掉 A 顺手把 B 也关了"的隐式耦合——
 * 例如 Worker 角色只负责投递，却会因为到期扫描被关闭而连 {@code @EnableScheduling} 都没有。
 *
 * <p>因此这里<b>无条件开启调度</b>，开关各自落在任务类上
 * （{@code pms.reservation.expiry.enabled} / {@code pms.outbox.poller.enabled} / {@code pms.outbox.monitor.enabled}）。
 * 开关全关时容器里没有任何 {@code @Scheduled} 方法，调度线程池不会真正创建线程，
 * 不存在之前担心的"空转线程"代价。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
