package cn.sec.hn.openbackupsaas.shared.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PmsMetrics} 的低基数约束是"采集端不爆"的硬保障，必须用测试钉死：
 * 任何高基数字段（UUID、订单号、手机号）越界都必须落到 {@code invalid}，
 * 而不是悄悄建一条以它为标签名的时间序列。
 */
class PmsMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final PmsMetrics metrics = new PmsMetrics(registry);

    @Test
    void command_records_under_its_own_tags() {
        metrics.command("reservation.create", PmsMetrics.Outcome.SUCCESS);
        metrics.command("reservation.create", PmsMetrics.Outcome.SUCCESS);

        double count = registry.find("pms.reservation.command")
                .tag("command", "reservation.create")
                .tag("outcome", "success")
                .counter()
                .count();
        assertEquals(2.0, count, 1e-9);
    }

    @Test
    void high_cardinality_value_collapses_to_invalid() {
        // 订单号（含大写与连字符）绝不能成为标签值，否则百万订单 = 百万条序列。
        metrics.command(UUID.randomUUID().toString(), PmsMetrics.Outcome.ERROR);

        double invalid = registry.find("pms.reservation.command")
                .tag("command", "invalid")
                .tag("outcome", "error")
                .counter()
                .count();
        assertEquals(1.0, invalid, 1e-9);

        // 原始越界值不能留下一条以它为标签的时间序列
        assertEquals(0, registry.find("pms.reservation.command")
                .tag("command", "ABC-123")
                .counters()
                .size());
    }

    @Test
    void task_channel_is_low_cardinality_safe() {
        // 合法小写渠道：同值多次调用合并到同一条。
        metrics.task("wechat", PmsMetrics.Outcome.SUCCESS);
        metrics.task("wechat", PmsMetrics.Outcome.RETRY);

        assertEquals(1.0, registry.find("pms.external_task.outcome")
                .tag("channel", "wechat").tag("outcome", "success").counter().count(), 1e-9);
        assertEquals(1.0, registry.find("pms.external_task.outcome")
                .tag("channel", "wechat").tag("outcome", "retry").counter().count(), 1e-9);

        // 渠道带大写/特殊字符：不转写，直接折叠为 invalid，避免大小写变体制造无限基数。
        metrics.task("WeChat-Pay!", PmsMetrics.Outcome.SUCCESS);
        assertEquals(1.0, registry.find("pms.external_task.outcome")
                .tag("channel", "invalid").tag("outcome", "success").counter().count(), 1e-9);
        // 原始非规范值不能留下以它为标签的时间序列
        assertEquals(0, registry.find("pms.external_task.outcome")
                .tag("channel", "WeChat-Pay!").counters().size());
    }

    @Test
    void inbox_duplicate_is_a_distinct_outcome() {
        metrics.inbox("duplicate");
        metrics.inbox("processed");

        assertEquals(1.0, registry.find("pms.inbox.outcome")
                .tag("outcome", "duplicate")
                .counter()
                .count(), 1e-9);
        assertEquals(1.0, registry.find("pms.inbox.outcome")
                .tag("outcome", "processed")
                .counter()
                .count(), 1e-9);
    }

    @Test
    void auth_with_reason_records_two_tags() {
        metrics.auth("login_failure", "bad_password");

        double count = registry.find("pms.security.auth")
                .tag("event", "login_failure")
                .tag("reason", "bad_password")
                .counter()
                .count();
        assertEquals(1.0, count, 1e-9);
    }

    @Test
    void expired_amount_is_summed() {
        metrics.expired(3);
        metrics.expired(2);

        double total = registry.find("pms.reservation.expired")
                .tag("reason", "hold_timeout")
                .counter()
                .count();
        assertEquals(5.0, total, 1e-9);
    }
}
