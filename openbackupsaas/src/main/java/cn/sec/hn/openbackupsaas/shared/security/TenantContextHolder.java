package cn.sec.hn.openbackupsaas.shared.security;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 线程级可信身份上下文（S1-03）。
 *
 * <p>两个必须遵守的纪律：
 * <ol>
 *   <li><b>谁绑定谁清理。</b>过滤器在 {@code finally} 里清理；任何手工 {@link #bind} 的代码
 *       也必须 {@code finally { clear(); }}，否则线程归还连接池/线程池后会把上一个请求的租户
 *       带给下一个请求（"串租户"）。</li>
 *   <li><b>后台任务不读"当前登录人"</b>（B-07）。Worker 线程没有请求上下文，
 *       必须用 {@link #callAs}/{@link #runAs} 显式传入任务自身的租户，而不是回退到某个全局值。</li>
 * </ol>
 *
 * <p>这里用 ThreadLocal 而不是请求作用域 Bean：领域服务与 Mapper 不在 Web 层，
 * 也不能为了拿上下文把 {@code HttpServletRequest} 一路透传。代价是必须严格清理，
 * 因此 {@link #callAs} 会恢复原值而不是简单置空。
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static void bind(TenantContext context) {
        if (context == null) {
            throw new IllegalArgumentException("绑定空的可信身份上下文没有意义；如需清空请调用 clear()");
        }
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<TenantContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** 需要身份才能继续的路径用它；缺上下文属于"未认证"而不是"无权限"。 */
    public static TenantContext require() {
        TenantContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("当前线程没有可信身份上下文");
        }
        return context;
    }

    public static boolean isBound() {
        return CURRENT.get() != null;
    }

    /** 后台任务：显式带上任务自己的租户执行，结束后恢复原值。 */
    public static <T> T callAs(TenantContext context, Supplier<T> action) {
        TenantContext previous = CURRENT.get();
        bind(context);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(TenantContext context, Runnable action) {
        callAs(context, () -> {
            action.run();
            return null;
        });
    }
}
