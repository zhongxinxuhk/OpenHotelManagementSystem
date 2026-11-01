package cn.sec.hn.openbackupsaas.guest.security;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 住客 PII 加密组件验收（ADR-018 §2.4）。
 *
 * <h2>这个类在防什么</h2>
 * 加密代码"能跑"和"是对的"差得很远，而错的形态都很安静：
 * <ul>
 *   <li>nonce 复用 —— 密文看起来正常，GCM 的安全性已崩，肉眼与功能测试都发现不了；</li>
 *   <li>缺密钥时降级成明文（或空密钥）—— 服务照常启动，违规数据照常入库；</li>
 *   <li>判重摘要不稳定（大小写、空格差异）—— 同一张证件算出两个摘要，唯一索引失效。</li>
 * </ul>
 * 这些只能靠断言发现。
 */
class GuestPiiCipherTest {

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static GuestPiiCipher newCipher() {
        return new GuestPiiCipher(randomKey(), randomKey());
    }

    @Test
    void encryptThenDecryptReturnsOriginal() {
        GuestPiiCipher cipher = newCipher();
        String plain = "11010519491231002X";
        Assertions.assertEquals(plain, cipher.decrypt(cipher.encrypt(plain)));
    }

    @Test
    void samePlaintextEncryptsDifferentlyEveryTime() {
        // 随机 nonce 的直接可观测结果：同一明文两次加密的密文不同。
        // 如果这里相等，说明 nonce 被固定/复用了——GCM 复用 nonce 会直接泄露明文异或值。
        GuestPiiCipher cipher = newCipher();
        String a = cipher.encrypt("11010519491231002X");
        String b = cipher.encrypt("11010519491231002X");
        Assertions.assertNotEquals(a, b, "两次加密结果相同，说明 nonce 没有随机化");
        Assertions.assertTrue(a.startsWith("v1:"), "密文应带版本前缀，便于将来换算法时新旧并存");
        Assertions.assertEquals("11010519491231002X", cipher.decrypt(a));
        Assertions.assertEquals("11010519491231002X", cipher.decrypt(b));
    }

    @Test
    void ciphertextIsNotRecognisableAsPlaintext() {
        GuestPiiCipher cipher = newCipher();
        String plain = "11010519491231002X";
        String token = cipher.encrypt(plain);
        Assertions.assertFalse(token.contains(plain), "密文里出现了明文片段");
        // Base64 解码后也不该出现明文字节
        byte[] raw = Base64.getDecoder().decode(token.substring(3));
        Assertions.assertFalse(new String(raw, java.nio.charset.StandardCharsets.UTF_8).contains(plain));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        // GCM 自带完整性校验：改一个字节必须解密失败，而不是解出垃圾数据。
        GuestPiiCipher cipher = newCipher();
        String token = cipher.encrypt("11010519491231002X");
        byte[] raw = Base64.getDecoder().decode(token.substring(3));
        raw[raw.length - 1] ^= 0x01;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
        Assertions.assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void indexIsStableForSameNormalizedValue() {
        GuestPiiCipher cipher = newCipher();
        String a = cipher.index("11010519491231002X");
        String b = cipher.index("11010519491231002X");
        Assertions.assertEquals(a, b, "判重摘要必须稳定，否则唯一索引失效");
        Assertions.assertEquals(64, a.length(), "HMAC-SHA256 十六进制应为 64 字符");
    }

    @Test
    void differentKeysProduceDifferentIndexes() {
        // 两个独立实例（密钥不同）对同一号码的摘要不同：说明摘要确实依赖密钥，
        // 而不是可被离线彩虹表枚举的裸哈希。
        String value = "11010519491231002X";
        Assertions.assertNotEquals(newCipher().index(value), newCipher().index(value));
    }

    @Test
    void missingKeysFailFastAtConstruction() {
        // 宁可起不来，也不要"没配就跑、悄悄存明文"。
        Assertions.assertThrows(IllegalStateException.class, () -> new GuestPiiCipher("", ""));
        Assertions.assertThrows(IllegalStateException.class,
                () -> new GuestPiiCipher(randomKey(), ""));
    }

    @Test
    void wrongAesKeyLengthIsRejected() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        Assertions.assertThrows(IllegalStateException.class,
                () -> new GuestPiiCipher(shortKey, randomKey()));
    }

    @Test
    void invalidBase64KeyIsRejected() {
        Assertions.assertThrows(IllegalStateException.class,
                () -> new GuestPiiCipher("not-base64!!", randomKey()));
    }
}
