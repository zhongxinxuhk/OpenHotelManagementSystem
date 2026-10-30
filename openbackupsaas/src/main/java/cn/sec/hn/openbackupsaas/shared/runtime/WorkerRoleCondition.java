package cn.sec.hn.openbackupsaas.shared.runtime;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Locale;

/**
 * 运行角色判定（S2-01）。
 *
 * <p>同一个制品按 {@code pms.runtime.role} 扮演不同角色：
 * <ul>
 *   <li>{@code api}：只跑 HTTP 接口，不跑任何后台投递（接口延迟不受 Broker 影响）；</li>
 *   <li>{@code worker}：只跑后台任务，配 {@code --spring.main.web-application-type=none} 不起 Tomcat；</li>
 *   <li>{@code all}（默认）：单进程开发/演示用，两者都跑。</li>
 * </ul>
 *
 * <p>为什么不拆成两个 Maven 模块：领域服务必须共用同一份实现，拆模块会立刻引出
 * "共享 jar 的版本对齐"这类运维问题，而当前目标只是<b>让进程能独立启停</b>——
 * 同制品 + 角色开关就能做到，且部署脚本只需换参数。
 *
 * <p>取值非法时<b>直接失败</b>，不静默退化为"不跑 Worker"：一个拼错的
 * {@code PMS_RUNTIME_ROLE=workers} 会让事件永远堆在库里，而日志里一片正常。
 */
public class WorkerRoleCondition implements Condition {

    static final String PROPERTY = "pms.runtime.role";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String role = context.getEnvironment().getProperty(PROPERTY, "all").trim().toLowerCase(Locale.ROOT);
        return switch (role) {
            case "worker", "all" -> true;
            case "api" -> false;
            default -> throw new IllegalStateException(
                    "未知的 " + PROPERTY + " 取值 '" + role + "'，只允许 api / worker / all");
        };
    }
}
