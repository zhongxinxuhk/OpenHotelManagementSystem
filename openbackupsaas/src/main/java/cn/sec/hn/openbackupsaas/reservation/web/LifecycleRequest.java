package cn.sec.hn.openbackupsaas.reservation.web;

import jakarta.validation.constraints.NotNull;

/**
 * 状态机命令入参（确认 / 取消 / No-show 共用，S1-09）。
 *
 * <p>{@code expectedVersion} 为 null 时跳过乐观版本校验；非 null 时与库内版本不一致将返回 409。
 * 幂等键进入 {@code X-Caller-Namespace} 作用域，避免跨调用主体复用。
 */
public record LifecycleRequest(@NotNull String idempotencyKey, Long expectedVersion) {
}
