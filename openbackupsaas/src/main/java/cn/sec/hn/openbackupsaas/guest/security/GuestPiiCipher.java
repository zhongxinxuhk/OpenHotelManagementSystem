package cn.sec.hn.openbackupsaas.guest.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 住客个人敏感信息（PII）的加密与判重（ADR-018 §2.4）。
 *
 * <h2>为什么是两个密钥、两套算法</h2>
 * 身份证号有两个互相独立的需求：**不能明文存**，以及**同一个人不能注册成两个账户**。
 * 一套手段做不到两件事：
 * <ul>
 *   <li><b>机密性</b> → AES-256-GCM（随机 nonce）。同一号码每次加密结果都不同，
 *       读表的人看不出两条记录是不是同一个人。</li>
 *   <li><b>判重</b> → HMAC-SHA256，单独一列，建唯一索引。密文无法等值比较，
 *       所以必须有这个确定性摘要。</li>
 * </ul>
 * 两个密钥分开，是因为它们的失效后果不同：HMAC 密钥泄漏只意味着别人能对号码算出摘要
 * （配合已知号码可反推"某账户是不是某人"），而 AES 密钥泄漏意味着**全部密文直接可解**。
 * 混用一个密钥会让前者升级成后者。
 *
 * <h2>为什么不用确定性加密兼做判重</h2>
 * 确定性加密（固定 nonce 或 ECB）确实能让等值查询工作，但有两个硬伤：
 * 它把"两条记录是否相同"直接暴露给任何能读表的人；更麻烦的是，密钥一旦泄漏，
 * <b>所有历史记录同时失效</b>——因为同一个密钥把同一明文映射成同一密文，换密钥就得全量重算，
 * 而那些旧密文仍然可用旧密钥解开。随机 nonce 至少让"逐个解密"成为攻击者必须承担的成本。
 *
 * <h2>密钥从哪来</h2>
 * 只从配置/环境变量读（{@code pms.guest.pii-key}、{@code pms.guest.pii-hmac-key}），
 * <b>不落代码库、不进迁移脚本、不写示例值</b>。
 * 缺密钥时**构造即失败**：宁可服务起不来，也不要出现"没配就跑起来、悄悄存明文"的降级路径——
 * 这种降级一旦发生，等发现时违规数据往往已经躺了几个月。
 */
@Component
public class GuestPiiCipher {

    /** 密文版本前缀。将来换算法或换密钥时按此区分，支持新旧并存解密。 */
    private static final String VERSION_PREFIX = "v1:";

    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** GCM 推荐 96 位 nonce；随机生成，**绝不复用**（复用会直接摧毁 GCM 的安全性）。 */
    private static final int NONCE_LENGTH = 12;

    /** GCM tag 128 位。 */
    private static final int TAG_BITS = 128;

    /** AES-256 要求 32 字节密钥。 */
    private static final int AES_KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec aesKey;
    private final SecretKeySpec hmacKey;

    /**
     * {@link Mac} 不是线程安全的，而每个请求都会用到判重。
     * 用 ThreadLocal 复用实例，避免每次 {@code Mac.getInstance} 的开销。
     */
    private final ThreadLocal<Mac> hmac;

    public GuestPiiCipher(@Value("${pms.guest.pii-key:}") String base64AesKey,
                          @Value("${pms.guest.pii-hmac-key:}") String base64HmacKey) {
        byte[] aes = decodeKey(base64AesKey, "pms.guest.pii-key");
        byte[] hmacBytes = decodeKey(base64HmacKey, "pms.guest.pii-hmac-key");
        if (aes.length != AES_KEY_BYTES) {
            // 不打印密钥本身，也不打印其长度以外的任何内容。
            throw new IllegalStateException(
                    "pms.guest.pii-key 必须是 " + AES_KEY_BYTES + " 字节的密钥（Base64 编码后注入）");
        }
        this.aesKey = new SecretKeySpec(aes, "AES");
        this.hmacKey = new SecretKeySpec(hmacBytes, HMAC_ALGORITHM);
        this.hmac = ThreadLocal.withInitial(() -> {
            try {
                Mac instance = Mac.getInstance(HMAC_ALGORITHM);
                instance.init(this.hmacKey);
                return instance;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("HMAC 初始化失败", e);
            }
        });
    }

    /**
     * 加密。返回 {@code v1:} + Base64(nonce ‖ ciphertext ‖ tag)。
     *
     * <p>每次调用都生成新的随机 nonce，因此**同一明文两次加密的结果不同**——
     * 这是有意的：相等性判断只能通过 {@link #index(String)} 走。
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("待加密内容不能为空");
        }
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            RANDOM.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[nonce.length + cipherText.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(cipherText, 0, payload, nonce.length, cipherText.length);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            // 不把底层异常信息带出去：它可能包含算法参数细节。
            throw new IllegalStateException("加密失败", e);
        }
    }

    /**
     * 解密。仅用于受审计的取证与人工核验流程，**不要在普通查询路径里调用**。
     */
    public String decrypt(String token) {
        if (token == null || !token.startsWith(VERSION_PREFIX)) {
            throw new IllegalArgumentException("密文格式不受支持");
        }
        byte[] payload = Base64.getDecoder().decode(token.substring(VERSION_PREFIX.length()));
        if (payload.length <= NONCE_LENGTH) {
            throw new IllegalArgumentException("密文长度不足");
        }
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            System.arraycopy(payload, 0, nonce, 0, NONCE_LENGTH);
            byte[] cipherText = new byte[payload.length - NONCE_LENGTH];
            System.arraycopy(payload, NONCE_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, nonce));
            // GCM 在这里同时校验完整性：密文被改过一个字节都会抛 AEADBadTagException。
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("解密失败：密文可能被篡改或密钥不匹配", e);
        }
    }

    /**
     * 判重摘要：HMAC-SHA256(值)，十六进制小写。
     *
     * <p>调用方应传入**已规范化**的值（例如身份证号去空格并转大写 X），
     * 否则同一张证件会算出两个摘要，唯一索引失效。
     */
    public String index(String normalizedValue) {
        if (normalizedValue == null || normalizedValue.isBlank()) {
            throw new IllegalArgumentException("判重内容不能为空");
        }
        Mac mac = hmac.get();
        mac.reset();
        byte[] digest = mac.doFinal(normalizedValue.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static byte[] decodeKey(String base64, String propertyName) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(
                    propertyName + " 未配置。住客实名数据必须加密存储，"
                            + "因此这两个密钥是启动的硬前提：请通过环境变量注入 "
                            + "PMS_GUEST_PII_KEY / PMS_GUEST_PII_HMAC_KEY（各 32 字节，Base64）。"
                            + "本地生成：openssl rand -base64 32");
        }
        try {
            return Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(propertyName + " 不是合法的 Base64", e);
        }
    }
}
