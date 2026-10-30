package cn.sec.hn.openbackupsaas.integration.domain;

/**
 * 落在任务行上的失败分类（S2-03）。
 *
 * <p>与 {@link TaskOutcome.Kind} 的区别：{@code Kind} 是"这次调用"的结论，
 * 这里是"这条任务当前停在哪一类问题上"。运维看的是后者——
 * 一批任务里 UNKNOWN 多，说明对方接口在超时；PERMANENT 多，说明对接参数错了。
 */
public enum ErrorClass {
    RETRYABLE,
    UNKNOWN,
    PERMANENT,
    RATE_LIMITED
}
