package cn.sec.hn.openbackupsaas.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 统一时间源。
 *
 * <p>为什么要有 Clock 而不是各处调 {@code OffsetDateTime.now()}：
 * 业务时间是可测与可复现的前提（锁房超时、营业日切换、夜审都依赖时间）。
 * 单一时钟入口还能避免不同机器/线程各自取时导致的时间倒流。
 *
 * <p>时区语义：数据库一律存 timestamptz（UTC 时间线），门店本地时区的换算在展示与营业日规则层完成（S0-05 / ADR-012）。
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
