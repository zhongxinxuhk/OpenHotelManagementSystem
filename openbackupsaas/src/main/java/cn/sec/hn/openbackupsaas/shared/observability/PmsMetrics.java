package cn.sec.hn.openbackupsaas.shared.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 业务指标的唯一出口（S2-06）。
 *
 * <h2>为什么要有这一层</h2>
 * 让每个服务各自 {@code Counter.builder(...)} 的结局是：同名指标在不同包里被各注册一次，
 * 标签名拼错没人发现，等看大盘时才发现「冲突」这一项永远为零。收口到一个类里，
 * 指标名与标签键就只有一份定义，改名字时编译器会告诉你漏了谁。
 *
 * <h2>标签的硬约束：低基数</h2>
 * 标签值只能是有限集合（命令名、结果枚举、渠道名）。**绝不接受订单号、房间号、
 * 租户 UUID、事件 UUID 这类高基数字段**——一个订单号一条时间序列，百万订单就是
 * 百万条序列，抓取端先被拖垮，云账单随后到。因此这里对标签值做白名单校验：
 * 越界的一律落到 {@code invalid} 并记 WARN，宁可在日志里看到一条刺眼的告警，
 * 也不要静默地把采集端撑爆。
 *
 * <h2>为什么结果用枚举而不是字符串</h2>
 * 「冲突」如果被写成 {@code conflict} / {@code CONFLICT} / {@code 冲突} 三种形式，
 * 大盘上就是三条互不相干的曲线。枚举把可能值钉死在编译期。
 *
 * <h2>什么不该进指标</h2>
 * 任何能反推到个人的信息：手机号、证件号、姓名、邮箱、完整口令。
 * 需要定位到个人时用**指纹**（见 {@code SessionTokens#fingerprint}），
 * 或在日志里带关联 ID，而不是把值塞进标签。
 */
@Component
public class PmsMetrics {

    /** 所有业务指标的公共前缀，抓取端按此过滤即可。 */
    public static final String PREFIX = "pms.";

    /**
     * 标签值白名单：仅小写字母、数字、下划线、点、连字符，长度 1–32。
     * 与「命令名 / 枚举名 / 渠道名」的实际取值范围对应；
     * UUID、订单号（含大写与 -）、手机号都过不了。
     */
    private static final Pattern LOW_CARDINALITY = Pattern.compile("[a-z0-9_.-]{1,32}");

    /** 非法标签值的兜底：指标仍然要报，但不能因此引入无限基数。 */
    private static final String INVALID = "invalid";

    private final MeterRegistry registry;

    public PmsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 订单写命令结果。 */
    public void command(String command, Outcome outcome) {
        increment("reservation.command", "command", command, "outcome", outcome);
    }

    /** 占用冲突：{@code kind} 取自冲突分类（枚举名小写）。 */
    public void conflict(String kind) {
        increment("occupancy.conflict", "kind", kind);
    }

    /** 到期承接：本轮实际迁移的订单数。 */
    public void expired(long count) {
        increment("reservation.expired", "reason", "hold_timeout", (double) count);
    }

    /** 外部任务处置结果（按渠道 + 结果计数）。 */
    public void task(String channel, Outcome outcome) {
        increment("external_task.outcome", "channel", channel, "outcome", outcome);
    }

    /**
     * Inbox 消费结论（PROCESSED / IGNORED / SKIPPED_STALE / PARKED_GAP）；
     * 重复投递走独立的 {@code duplicate} 取值（非枚举成员，仅统计重投频次）。
     */
    public void inbox(String outcome) {
        increment("inbox.outcome", "outcome", outcome);
    }

    /** 安全事件（LOGIN_SUCCESS / LOGIN_FAILURE / LOGOUT / PASSWORD_CHANGED）。 */
    public void auth(String event) {
        increment("security.auth", "event", event);
    }

    /** 安全事件（带原因，原因必须是有限集合的枚举名）。 */
    public void auth(String event, String reason) {
        Counter.builder(PREFIX + "security.auth")
                .tag("event", tagValue(event))
                .tag("reason", tagValue(reason))
                .register(registry)
                .increment();
    }

    /**
     * 被限流拒绝的请求（J-05）。
     *
     * <p>标签只放**档位名**（{@code guest.login} 这类有限集合），绝不放客户端地址——
     * 一个 IP 一条时间序列，被刷时采集端先崩，而这张图本来要看的是"哪个入口被打"。
     * 需要定位到具体来源时看 WARN 日志（带 client 与 requestId）。
     */
    public void rateLimited(String rule) {
        increment("security.rate_limited", "rule", rule);
    }

    private void increment(String metric, String keyA, Object valueA, String keyB, Object valueB) {
        increment(metric, keyA, valueA, keyB, valueB, 1.0);
    }

    private void increment(String metric, String keyA, Object valueA, String keyB, Object valueB, double amount) {
        if (amount <= 0) {
            return;
        }
        Counter.builder(PREFIX + metric)
                .tag(keyA, tagValue(valueA))
                .tag(keyB, tagValue(valueB))
                .register(registry)
                .increment(amount);
    }

    /** 单标签计数。 */
    private void increment(String metric, String keyA, Object valueA) {
        Counter.builder(PREFIX + metric)
                .tag(keyA, tagValue(valueA))
                .register(registry)
                .increment();
    }

    /** 单标签、带增量的计数（用于"本轮释放 N 单"这类批量语义）。 */
    private void increment(String metric, String keyA, Object valueA, double amount) {
        if (amount <= 0) {
            return;
        }
        Counter.builder(PREFIX + metric)
                .tag(keyA, tagValue(valueA))
                .register(registry)
                .increment(amount);
    }

    /**
     * 标签值规范化：枚举名统一小写，其余类型按字符串校验。
     * 越界返回 {@code invalid} —— 把事故挡在采集端之前。
     */
    private static String tagValue(Object value) {
        String text = value instanceof Enum<?> enumValue
                ? enumValue.name().toLowerCase(Locale.ROOT)
                : String.valueOf(value);
        if (text == null || text.isBlank() || !LOW_CARDINALITY.matcher(text).matches()) {
            return INVALID;
        }
        return text;
    }

    /**
     * 业务结果。
     *
     * <p>取值必须保持有限且稳定：它们会直接变成时序标签。
     * 新增取值等同于新增一条曲线，只在"运维确实需要单独看它"时才加。
     */
    public enum Outcome {

        /** 业务成功。 */
        SUCCESS,
        /** 与当前状态冲突（409）：业务结论，不是故障。 */
        CONFLICT,
        /** 请求不可受理（400）：客户端问题。 */
        REJECTED,
        /** 技术性失败：需要运维介入。 */
        ERROR,
        /** 确定未生效，已安排退避重试。 */
        RETRY,
        /** 尝试次数耗尽，进入终局。 */
        DEAD,
        /** 结果未知且无法自动处置，转人工。 */
        MANUAL,
        /** 因限流/熔断本轮未调用。 */
        SKIPPED
    }
}
