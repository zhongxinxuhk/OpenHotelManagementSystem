package cn.sec.hn.openbackupsaas.platform.security;

import cn.sec.hn.openbackupsaas.shared.security.SessionTokens;

/**
 * 平台会话令牌的派生（ADR-019 §2.2）。
 *
 * <p>与住客同理，只是域前缀不同：{@code SHA-256("platform-session:v1:" || token)}。
 * 三个平面的令牌因此互不可换——同一个令牌串在三张会话表里的哈希值各不相同。
 */
public final class PlatformSessionTokens {

    private static final String DOMAIN = "platform-session:v1:";

    private PlatformSessionTokens() {
    }

    public static String generate() {
        return SessionTokens.generate();
    }

    public static String hash(String token) {
        return SessionTokens.hashInDomain(DOMAIN, token);
    }

    public static String fingerprint(String token) {
        return hash(token).substring(0, 12);
    }
}
