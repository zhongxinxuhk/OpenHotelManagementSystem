package cn.sec.hn.openbackupsaas.guest.security;

import cn.sec.hn.openbackupsaas.shared.security.SessionTokens;

/**
 * 住客会话令牌的生成与派生（ADR-019 §2.2）。
 *
 * <p>与员工令牌的差别只有一处，但它是整套隔离的关键：**哈希域分离**。
 * 员工令牌入库哈希是 {@code SHA-256(token)}，住客令牌是
 * {@code SHA-256("guest-session:v1:" || token)}。
 *
 * <p>为什么值得多套一层前缀：
 * <ul>
 *   <li>没有前缀时，同一个令牌串在两张表里的哈希**完全相同**。只要有人写出
 *       "先查 user_session、查不到再兜底查 guest_session"这种代码，一个员工令牌
 *       就能被认成住客——或者反过来。而这种代码看起来还挺"健壮"。</li>
 *   <li>加了前缀，三张会话表里的哈希值互不相同，"兜底查"即使被写出来也命不中。</li>
 *   <li>代价是一次字符串拼接与一个容易忘的约定，所以本平面的令牌**只能**走本类，
 *       不允许直接调 {@link SessionTokens#hash}（那会退化成员工平面的派生方式）。</li>
 * </ul>
 *
 * <p>明文令牌只在签发响应里出现一次，任何日志、异常、审计都不得包含它。
 */
public final class GuestSessionTokens {

    /**
     * 域前缀。带版本号：将来若要改派生方式，新旧前缀可并存，
     * 老会话到期自然淘汰，不需要一次性踢掉所有在线住客。
     */
    private static final String DOMAIN = "guest-session:v1:";

    private GuestSessionTokens() {
    }

    /** 生成新令牌明文；调用方只能把它放进响应体。 */
    public static String generate() {
        return SessionTokens.generate();
    }

    /** 令牌 → 库内哈希。**带住客域前缀**，与员工/平台令牌不可互换。 */
    public static String hash(String token) {
        return SessionTokens.hashInDomain(DOMAIN, token);
    }

    /** 日志用短指纹：能区分同一令牌的多次请求，又不足以还原令牌。 */
    public static String fingerprint(String token) {
        return hash(token).substring(0, 12);
    }
}
