package cn.sec.hn.openbackupsaas.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 会话令牌的生成与派生（S1-03）。
 *
 * <p>规则：
 * <ul>
 *   <li>令牌是 32 字节密码学随机数的 Base64URL 文本（43 字符，无填充）；
 *       不携带任何身份信息，因此不需要签名密钥，也就不会出现"密钥轮换后全量会话失效"的连锁。</li>
 *   <li>库内只存 SHA-256 的十六进制（64 字符）。**明文令牌只在登录响应里出现一次**，
 *       任何日志、异常消息、审计明细都不得包含明文。</li>
 *   <li>用 SHA-256 而不是 BCrypt：令牌本身已是高熵随机串，不需要慢哈希；
 *       慢哈希会让每个请求都付出一次 BCrypt 成本，得不偿失。</li>
 * </ul>
 */
public final class SessionTokens {

    /** 令牌字节长度：256 位熵，远超暴力猜测可行性。 */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private SessionTokens() {
    }

    /** 生成新令牌明文；调用方只能把它放进响应体，不得落库或写日志。 */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /**
     * 令牌 → 库内哈希（员工平面，无域前缀）。查询与入库都必须经过本方法，
     * 禁止两处各写一套。
     *
     * <p>员工平面保持无前缀是**为了不迁移存量会话**：改派生方式会让所有在线员工
     * 被踢下线。住客与平台两条新平面从诞生起就带域前缀（见 {@link #hashInDomain}）。
     */
    public static String hash(String token) {
        return hashInDomain("", token);
    }

    /**
     * 带域前缀的令牌哈希（ADR-019 §2.2）。
     *
     * <p><b>为什么需要域分离</b>：三个平面的会话存在三张表里，如果哈希算法完全相同，
     * 同一个令牌串在每张表的哈希值就**一模一样**。此时只要有人写出
     * "先查 A 表、查不到兜底查 B 表"这种看着挺健壮的代码，一个平面的令牌
     * 就会被另一个平面认出来。加了域前缀，这类兜底查询不可能命中。
     *
     * <p>{@code domain} 传空串等价于无前缀，仅供员工平面使用。
     */
    public static String hashInDomain(String domain, String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("令牌不能为空");
        }
        String prefix = domain == null ? "" : domain;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] material = (prefix + token).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(material));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必须提供的算法；走到这里说明运行环境被裁剪，属于不可运行状态。
            throw new IllegalStateException("当前 JVM 不提供 SHA-256", ex);
        }
    }

    /**
     * 日志用短指纹：能区分"同一个令牌的多次请求"，又不足以还原令牌。
     * 排查会话问题时用它，不要打印明文或完整哈希。
     */
    public static String fingerprint(String token) {
        return hash(token).substring(0, 12);
    }
}
