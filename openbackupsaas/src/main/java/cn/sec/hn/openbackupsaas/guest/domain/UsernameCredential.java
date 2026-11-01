package cn.sec.hn.openbackupsaas.guest.domain;

import java.util.UUID;

/**
 * 用户名登录查询结果（ADR-019 §2.9）。
 *
 * <p>只包含登录比对所需的两列。刻意**不带显示名、状态等任何其它字段**：
 * 那些由 {@code findAccount} 单独提供，登录服务在比对通过之后才去取——
 * 让"读账户资料"发生在"口令比对通过之后"，失败路径上不产生多余的读取。
 */
public record UsernameCredential(
        UUID accountId,
        String secretHash
) {
}
