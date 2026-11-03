package cn.sec.hn.openbackupsaas.guest.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 实名核验渠道配置（ADR-019 §2.6）。
 *
 * <p>需求："权威接口支持在管理员后台自定义设置，后续再说明对接哪些权威核验接口"。
 * 所以本记录是**配置载体**：{@code code} 用来找实现，{@code endpoint} 与参数由后台填。
 * 本轮没有任何 AUTHORITY 实现的注册，配置填好了也只是存着——这一点必须在界面上说清，
 * 否则运维会以为"配完就生效了"。
 *
 * <p>{@code credentialRef} 只存**引用**（指向密钥管理里的条目），不存密钥本身：
 * 配置表会被导出、会被贴进工单，密钥进了这张表就等于进了所有备份。
 *
 * <p>熔断状态存在这里，但**人工审核路径不读本表**（见
 * {@code GuestVerificationDecisionService} 的构造器）——这是"权威接口熔断时
 * 人工审核照常"的实现保证。
 */
public record VerificationChannel(
        UUID id,
        String code,
        String name,
        ChannelKind kind,
        String endpoint,
        String credentialRef,
        int timeoutMs,
        int priority,
        ChannelStatus status,
        CircuitState circuitState,
        int consecutiveFailures,
        OffsetDateTime openedUntil,
        OffsetDateTime lastFailureAt,
        String lastFailureReason,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /** AUTHORITY = 权威核验接口；MANUAL = 人工审核（永远可用，不受熔断影响）。 */
    public enum ChannelKind {
        AUTHORITY,
        MANUAL
    }

    public enum ChannelStatus {
        ACTIVE,
        INACTIVE
    }

    /**
     * 熔断状态。
     *
     * <p>{@code OPEN} 表示"暂时不要调它"：{@code openedUntil} 之前一律跳过。
     * 到点后由下一次调用转 {@code HALF_OPEN} 试探一次——全自动恢复，
     * 不需要有人记得去点"复位"。
     */
    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * 此刻是否应该跳过这个渠道。
     *
     * <p>判据只有"状态 + 时间"，不查数据库、不调外部——调用方拿到的记录自己就能算。
     * 时间用参数传入而不是 {@code now()}：让调用方决定"现在"，测试才能确定性地验证熔断窗口。
     */
    public boolean shouldSkip(OffsetDateTime moment) {
        if (status != ChannelStatus.ACTIVE) {
            return true;
        }
        if (circuitState == CircuitState.OPEN) {
            return openedUntil == null || openedUntil.isAfter(moment);
        }
        return false;
    }
}
