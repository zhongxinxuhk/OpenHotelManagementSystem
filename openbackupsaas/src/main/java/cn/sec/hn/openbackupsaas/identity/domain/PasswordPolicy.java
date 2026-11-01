package cn.sec.hn.openbackupsaas.identity.domain;

/**
 * 口令策略（S1-03 建立，S3-02 抽出为独立类型）。
 *
 * <h2>为什么不在认证服务里就地校验</h2>
 * 现在有两个地方要判定口令是否合格：用户自己改密（认证服务），以及管理员开通员工时
 * 生成初始口令（管理服务）。规则留在私有方法里，第二处只能复制一段，然后随着
 * "加上禁用弱口令字典"之类的调整慢慢分叉——分叉后谁都没报错，只是某条路径变松了。
 *
 * <h2>规则本身</h2>
 * 只做长度与"不含登录名"。复杂度规则（大小写/符号/弱口令字典）等 S6-05 安全验收补，
 * 因为强行要求符号会诱导出 {@code Password1!} 这类可预测口令，收益存疑。
 */
public final class PasswordPolicy {

    /** 12 位：NIST SP 800-63B 对单因素口令的最低建议长度。 */
    public static final int MIN_LENGTH = 12;

    /** 128 位上限：BCrypt 只处理前 72 字节，再长没有意义，截断差异反而会造成困惑。 */
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {
    }

    /**
     * 校验口令是否可用于该账号。
     *
     * @throws IllegalArgumentException 不满足策略；消息直接面向操作者
     */
    public static void requireAcceptable(String rawPassword, String username) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("新口令长度不足 " + MIN_LENGTH + " 位");
        }
        if (rawPassword.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("新口令长度超过 " + MAX_LENGTH + " 位");
        }
        if (username != null && !username.isBlank()
                && rawPassword.toLowerCase(java.util.Locale.ROOT).contains(username.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("新口令不能包含登录名");
        }
    }
}
