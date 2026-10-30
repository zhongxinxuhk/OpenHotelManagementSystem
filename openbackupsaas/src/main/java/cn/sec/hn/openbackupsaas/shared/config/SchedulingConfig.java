package cn.sec.hn.openbackupsaas.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务装配（S1-10）。
 *
 * <p>为什么开关放在这里而不是只加在任务类上：{@code @EnableScheduling} 一旦生效，
 * 容器里<b>所有</b> {@code @Scheduled} 方法都会起跑。若只给任务类加条件、仍无条件开启调度，
 * 关掉开关后仍会留下一个空转的调度线程池——在无库的 {@code test} 档案与
 * {@code integration} 档案里都只会制造噪声。两者必须同进同出。
 *
 * <p>默认开启（{@code matchIfMissing = true}）：生产环境忘记配开关的代价是"锁房永不释放"，
 * 比"多跑一个扫描线程"严重得多。测试档案显式关闭，测试自己调用服务方法。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "pms.reservation.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
