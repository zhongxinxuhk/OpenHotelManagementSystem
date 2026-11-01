package cn.sec.hn.openbackupsaas.identity.service;

import cn.sec.hn.openbackupsaas.identity.domain.PasswordPolicy;

import java.security.SecureRandom;

/**
 * 初始口令生成（S3-02）。
 *
 * <h2>为什么由服务端生成，而不是让管理员填</h2>
 * 让管理员填就会走向两个现实结果：要么填一个大家都猜得到的（"酒店123"），
 * 要么把同一个口令发给多个新员工。两种都不是能靠培训和制度解决的问题——
 * 只有不给他这个机会。
 *
 * <h2>为什么要求"一次性展示"</h2>
 * 口令只在创建响应里出现一次，库内只有 BCrypt 哈希，服务端事后也无法再取回。
 * 配合账号上的 {@code must_change_password}，管理员的这份副本在员工首次登录后
 * 立即作废——责任边界由此清楚：谁能动这个账号，日志上分得出来。
 *
 * <h2>字符集为什么不带 0/O/1/l/I</h2>
 * 初始口令总有人要口头念或手抄一次。形近字符是这一步最常见的错误来源，
 * 而丢掉的熵（6 个字符）在 16 位长度下完全不影响强度。
 */
public final class InitialPasswordGenerator {

    /** 去掉了 0 O 1 l I 的 Base58 风格字符集。 */
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    /** 16 位 × log2(57) ≈ 93 bit：远超口令策略下限，也不需要加符号规则。 */
    private static final int LENGTH = 16;

    /**
     * 重新抽取的保底次数。
     *
     * <p>只在"抽出来的口令恰好含登录名"时重抽（3 位登录名命中概率约万分之一），
     * 正常一次就通过。给上限是为了不在理论上退化成死循环。
     */
    private static final int MAX_ATTEMPTS = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private InitialPasswordGenerator() {
    }

    /**
     * 生成一个满足 {@link PasswordPolicy} 的初始口令。
     *
     * @param username 不能出现在口令里的登录名；可为空（此时只保证长度与随机性）
     */
    public static String generate(String username) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = draw();
            if (username == null || username.isBlank()
                    || !candidate.toLowerCase(java.util.Locale.ROOT)
                    .contains(username.toLowerCase(java.util.Locale.ROOT))) {
                // 用同一套策略自检：生成器与校验器分家的后果是"发出去的口令自己都不收"。
                PasswordPolicy.requireAcceptable(candidate, username);
                return candidate;
            }
        }
        throw new IllegalStateException("多次未抽到不含登录名的口令，请更换登录名后重试");
    }

    private static String draw() {
        char[] buffer = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            buffer[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(buffer);
    }
}
