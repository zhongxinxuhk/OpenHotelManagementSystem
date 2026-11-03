package cn.sec.hn.openbackupsaas.integration.service;

import java.time.Duration;

/**
 * 单个渠道的调用策略（S2-03）。
 *
 * <p>这些参数是<b>按渠道</b>而不是全局的：OTA 普遍比自建网关更苛刻（限流更紧、超时更短、
 * 拒绝后重试更没用）。用一套全局参数，结局只能是"为了照顾最差的一家，所有渠道都变慢"。
 *
 * @param maxAttempts       最大尝试次数（含首次）
 * @param minInterval       两次调用的最小间隔；用于对方明确的 QPS 限制
 * @param timeout           单次调用超时；超时即 {@code unknown}
 * @param circuitThreshold  连续失败多少次后熔断该渠道
 * @param circuitCooldown   熔断后冷却时长
 */
public record ChannelPolicy(
        String channel,
        int maxAttempts,
        Duration minInterval,
        Duration timeout,
        int circuitThreshold,
        Duration circuitCooldown
) {

    public ChannelPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 至少为 1：" + maxAttempts);
        }
        if (circuitThreshold < 1) {
            throw new IllegalArgumentException("circuitThreshold 至少为 1：" + circuitThreshold);
        }
    }
}
