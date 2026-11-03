package cn.sec.hn.openbackupsaas.shared.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 限流规则登记表（J-05）：把"哪些入口、多严"收敛成一张有顺序的表。
 *
 * <h2>定档依据：越贵的动作越严</h2>
 * <ul>
 *   <li><b>登录 / 注册 / 发码</b>——一次请求背后是 BCrypt 计算或一条要花钱的短信。
 *       这类接口的限额不是"防刷页"而是"防成本"：发码接口被刷等于替别人付短信费。</li>
 *   <li><b>匿名查询</b>——只在公开面开放后才存在（当前默认关闭），限额的用途是
 *       防止别人把全站房态爬成一张表（J-02 的已知风险）。</li>
 *   <li><b>已登录接口</b>——本轮不定档：有身份就有可追责的账号，且批量导出类业务
 *       会被"每分钟 N 次"误伤。这块留给风控（依赖验证码通道与真实渠道），
 *       现在加一条拍脑袋的全局限额，只会让人在生产上调大它。</li>
 * </ul>
 *
 * <h2>顺序即优先级（见 {@link RateLimitRule}）</h2>
 * 具体路径必须排在通配路径之前。
 *
 * <h2>为什么用 {@link Environment} 而不是宽松绑定的 Map</h2>
 * 同 {@code ChannelPolicyRegistry}：键名拼错时宽松绑定会静默退化成默认值，
 * 而限流"配了但没生效"是最难发现的一类问题。这里显式逐项取值，
 * 并在启动日志里打印最终生效的档位——出问题时第一眼看日志就知道配没配上。
 *
 * <p>改限额需要重启（配置在构造期解析）。事故期要临时收紧，
 * 用 {@code PMS_RATE_LIMIT_RULES_<名字>_PERMITS} 改环境变量再滚动重启；
 * 真正需要热更新的场景属于网关层，不在应用内做。
 */
@Component
public class RateLimitRules {

    private static final Logger log = LoggerFactory.getLogger(RateLimitRules.class);

    /** 覆盖键前缀：{@code pms.rate-limit.rules.<名>.permits} / {@code .window}。 */
    private static final String OVERRIDE_PREFIX = "pms.rate-limit.rules.";

    private final List<RateLimitRule> rules;

    public RateLimitRules(Environment environment) {
        this.rules = build(environment);
        log.info("限流档位生效（{} 条）：{}", rules.size(),
                rules.stream().map(rule -> rule.policy().describe()).toList());
    }

    /**
     * 解析请求命中的档位。
     *
     * @return 命中的规则；没有规则覆盖该路径时返回 {@code null}（不限流）
     */
    public RateLimitRule resolve(String method, String path) {
        for (RateLimitRule rule : rules) {
            if (rule.matches(method, path)) {
                return rule;
            }
        }
        return null;
    }

    /** 供启动日志之外的地方（运维接口、测试）读当前档位。 */
    public List<RateLimitRule> snapshot() {
        return rules;
    }

    private static List<RateLimitRule> build(Environment environment) {
        List<RateLimitRule> list = new ArrayList<>();

        // ===== 住客平面：匿名可写的入口（最贵） =====
        // 注册：一个账号一次，5 次/10 分钟足够正常用户改主意两次
        list.add(rule(environment, HttpMethod.POST, "/api/v1/guest/auth/register",
                "guest.register", 5, Duration.ofMinutes(10)));
        // 登录：10 次/5 分钟。与"账号级失败计数"互补——前者挡撞库（换账号），
        // 后者挡定向爆破（盯一个账号），两者缺一都会留下可行的攻击形状
        list.add(rule(environment, HttpMethod.POST, "/api/v1/guest/auth/login",
                "guest.login", 10, Duration.ofMinutes(5)));
        // 发码：5 次/10 分钟。这条挡的是短信轰炸与短信账单
        list.add(rule(environment, HttpMethod.POST, "/api/v1/guest/auth/bindings/challenge",
                "guest.binding.challenge", 5, Duration.ofMinutes(10)));
        // 确认码：10 次/10 分钟。挑战自身还有 5 次尝试上限，这里是账号级外的地址级兜底
        list.add(rule(environment, HttpMethod.POST, "/api/v1/guest/auth/bindings/confirm",
                "guest.binding.confirm", 10, Duration.ofMinutes(10)));
        // 用户名绑定 / 解绑：要已登录，限额只防脚本化批量操作
        list.add(rule(environment, HttpMethod.POST, "/api/v1/guest/auth/bindings/username",
                "guest.binding.username", 10, Duration.ofMinutes(10)));
        // 验证码登录发码（R-20261102-07）：与绑定发码同档——这条挡的是
        // 邮件轰炸与邮件账单；注意**具体路径必须在通配 /guest/auth/** 之前**登记
        list.add(rule(environment, HttpMethod.POST, "/api/v1/guest/auth/code-login/challenge",
                "guest.code-login.challenge", 5, Duration.ofMinutes(10)));
        // 验证码登录提交：未认证流量，比绑定确认更严（登录是攻击面最大的入口）
        list.add(rule(environment, HttpMethod.POST, "/api/v1/guest/auth/code-login",
                "guest.code-login.confirm", 10, Duration.ofMinutes(10)));
        // 忘记密码发码（R-20261103-01）：与登录发码同档——挡邮件轰炸与账单
        list.add(rule(environment, HttpMethod.POST, "/api/v1/guest/auth/password-reset/challenge",
                "guest.password-reset.challenge", 5, Duration.ofMinutes(10)));
        // 重置提交：未认证流量，与登录提交同档
        list.add(rule(environment, HttpMethod.POST, "/api/v1/guest/auth/password-reset",
                "guest.password-reset.confirm", 10, Duration.ofMinutes(10)));
        // 已登录改密：要认证 + 当前口令，限额只防脚本化滥用
        list.add(rule(environment, HttpMethod.POST, "/api/v1/guest/auth/password",
                "guest.password.change", 10, Duration.ofMinutes(10)));
        // 其余住客认证接口（方式清单、登出、我的凭证列表）
        list.add(rule(environment, null, "/api/v1/guest/auth/**",
                "guest.auth", 60, Duration.ofMinutes(1)));

        // ===== 商家平面与平台平面：登录入口 =====
        // 员工登录限额比住客松一点：同一出口 IP 后面往往是一整个前台（NAT），
        // 员工换班、几个终端同时登录是正常现象
        list.add(rule(environment, HttpMethod.POST, "/api/v1/auth/login",
                "staff.login", 20, Duration.ofMinutes(5)));
        // 平台登录用低频特权账号，限额最严
        list.add(rule(environment, HttpMethod.POST, "/api/v1/platform/auth/login",
                "platform.login", 5, Duration.ofMinutes(5)));

        // ===== 匿名公开面（默认关闭，开启前必须有这一条） =====
        list.add(rule(environment, HttpMethod.GET, "/api/v1/public/**",
                "public.catalog", 60, Duration.ofMinutes(1)));

        return List.copyOf(list);
    }

    private static RateLimitRule rule(Environment environment,
                                      HttpMethod method,
                                      String pathPattern,
                                      String name,
                                      int defaultPermits,
                                      Duration defaultWindow) {
        String prefix = OVERRIDE_PREFIX + name + ".";
        Integer permits = environment.getProperty(prefix + "permits", Integer.class);
        Duration window = environment.getProperty(prefix + "window", Duration.class);
        RateLimitPolicy policy = new RateLimitPolicy(
                name,
                permits == null ? defaultPermits : permits,
                window == null ? defaultWindow : window);
        return new RateLimitRule(method, pathPattern, policy);
    }
}
