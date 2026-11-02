package cn.sec.hn.openbackupsaas.guest.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 住客登录凭证类型（ADR-018 §2.3）。
 *
 * <p>{@code CORP_SSO} 是**预留**：企业对公账户与单位统一身份登录尚未定案（DEC-17），
 * 先把类型占住，避免将来加一个值就要改所有 switch。
 *
 * <p>{@code USERNAME} 是 2026-11-01 需求方追加的登录方式（用户名+密码，
 * ADR-019 §2.9）：不依赖任何外部通道，所以它是当前唯一 {@link #isImplemented() 已实现} 的方式。
 */
public enum GuestProvider {

    /** 用户名 + 密码。唯一的自持凭证：归属由"知道密码"本身证明。 */
    USERNAME,
    PHONE,
    EMAIL,
    WECHAT,
    QQ,
    CNID_SSO,
    APPLE,
    CORP_SSO;

    /**
     * 手机号：**强制 E.164**（必须带 {@code +} 与国家码），不接受空格、横线与括号。
     *
     * <p>为什么要求带 {@code +}：唯一索引建在 {@code (provider, subject)} 上，
     * 而 {@code +8613800138000} 与 {@code 8613800138000} 是两个不同的字符串。
     * 如果两种写法都接受，同一个人就能注册出两个账户，判重形同虚设。
     * 与其在服务层猜"这个号码属于哪个国家"，不如要求客户端按 E.164 提交——
     * 三个客户端都是我们自己的，这条规则可以在客户端侧一次做对。
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+[1-9]\\d{6,14}$");

    /** 邮箱：只做结构性校验，真实性靠验证码/回调，不靠正则碰运气。 */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    /**
     * 用户名：与员工账号同一套字形规则（V2 的 {@code ck_app_user_username_fmt}），
     * 降低两套账号体系之间的认知负担。不含大小写字母——注册时一律小写化。
     */
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");

    /**
     * 把外部传入的凭证主体规范化。
     *
     * <p><b>为什么必须规范化</b>：唯一索引建在 {@code (provider, subject)} 上，
     * 如果 {@code +8613800138000} 与 {@code 8613800138000}、或大小写不同的邮箱各存一份，
     * 同一个人就能注册出多个账户，唯一约束形同虚设。
     *
     * <p>第三方来源（微信/QQ/统一身份/Apple）的 subject 是对方下发的稳定标识，
     * 这里只去空白，不做改写——改写会让"授权时的 subject"与"登录时回传的 subject"对不上。
     */
    public String normalize(String rawSubject) {
        if (rawSubject == null || rawSubject.isBlank()) {
            throw new IllegalArgumentException("凭证主体不能为空");
        }
        String trimmed = rawSubject.trim();
        return switch (this) {
            case PHONE -> {
                String compact = trimmed.replaceAll("[\\s\\-()]", "");
                if (!PHONE_PATTERN.matcher(compact).matches()) {
                    throw new IllegalArgumentException(
                            "手机号必须是 E.164 格式（带 + 与国家码，例如 +8613800138000）：" + rawSubject);
                }
                yield compact;
            }
            case EMAIL -> {
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (!EMAIL_PATTERN.matcher(lower).matches()) {
                    throw new IllegalArgumentException("邮箱格式不合法：" + rawSubject);
                }
                yield lower;
            }
            case USERNAME -> {
                // 小写化：Admin 与 admin 必须是同一个账号。与邮箱同一条理由——
                // 唯一索引按字符串判等，大小写两种写法都收等于允许一人多号。
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (!USERNAME_PATTERN.matcher(lower).matches()) {
                    throw new IllegalArgumentException(
                            "用户名须为 3-64 位小写字母/数字，可含 . _ -，且以字母或数字开头：" + rawSubject);
                }
                yield lower;
            }
            default -> trimmed;
        };
    }

    /**
     * 该类型的凭证是否**需要**外部归属验证（验证码 / OAuth 回调）。
     *
     * <p>{@code USERNAME} 是唯一的例外：注册时由创建者设置密码，
     * "知道密码"本身就是归属证明——这和员工账号首次开通是同一个逻辑。
     * 其余类型都依赖外部通道证明"这个手机号/微信真的是你的"。
     */
    public boolean requiresOwnershipProof() {
        return this != USERNAME;
    }

    /**
     * 该登录方式当前是否**已实现**（ADR-019 §2.9）。
     *
     * <p>登录方式的可用性 = 本方法 **∧** 策略开关（{@code guest_login_policy}）。
     * USERNAME 自 R-20261101-32；EMAIL 自 R-20261102-07（验证码登录消费侧接通，
     * 投递通道在 R-20261102-01 落地）。把未实现的方式在控制台里打开，
     * 对外仍然是"未开放"——这样"接通新通道"永远是代码发布，
     * "暂时关掉"永远是控制台开关，两者不会混。PHONE 要短信服务商，
     * 第三方要 OAuth 回调，仍未接。
     */
    public boolean isImplemented() {
        return this == USERNAME || this == EMAIL;
    }
}
