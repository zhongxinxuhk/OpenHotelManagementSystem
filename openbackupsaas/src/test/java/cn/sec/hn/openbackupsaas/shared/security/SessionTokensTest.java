package cn.sec.hn.openbackupsaas.shared.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌生成与派生（纯单元测试，不连库）。
 *
 * <p>要守住的是三件事：**长度足够、每次不同、同一令牌哈希稳定**。
 * 哈希不稳定会导致"登录成功但下一个请求 401"这种最难查的故障；
 * 长度不足则直接削弱会话安全。
 */
class SessionTokensTest {

    @Test
    void generatedTokensAreUniqueAndUrlSafe() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = SessionTokens.generate();
            assertEquals(43, token.length(), "32 字节 Base64URL 无填充应为 43 字符");
            assertTrue(token.matches("^[A-Za-z0-9_-]+$"), "令牌必须是 URL 安全字符，避免头和查询串转义问题");
            assertTrue(seen.add(token), "令牌重复意味着随机源有问题");
        }
    }

    @Test
    void hashIsStableAndSixtyFourHexChars() {
        String token = SessionTokens.generate();
        String first = SessionTokens.hash(token);
        String second = SessionTokens.hash(token);

        assertEquals(first, second, "同一令牌的哈希必须稳定");
        assertEquals(64, first.length());
        assertTrue(first.matches("^[0-9a-f]{64}$"));
        assertNotEquals(token, first);
    }

    @Test
    void differentTokensHashDifferently() {
        assertNotEquals(SessionTokens.hash(SessionTokens.generate()), SessionTokens.hash(SessionTokens.generate()));
    }

    @Test
    void fingerprintIsPrefixOfHashAndNotTheToken() {
        String token = SessionTokens.generate();
        assertEquals(SessionTokens.hash(token).substring(0, 12), SessionTokens.fingerprint(token));
        assertNotEquals(token, SessionTokens.fingerprint(token));
    }

    @Test
    void blankTokenRejected() {
        assertThrows(IllegalArgumentException.class, () -> SessionTokens.hash(""));
        assertThrows(IllegalArgumentException.class, () -> SessionTokens.hash("   "));
        assertThrows(IllegalArgumentException.class, () -> SessionTokens.hash(null));
    }
}
