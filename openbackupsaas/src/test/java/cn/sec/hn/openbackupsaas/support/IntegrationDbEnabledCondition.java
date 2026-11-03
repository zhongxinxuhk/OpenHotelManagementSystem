package cn.sec.hn.openbackupsaas.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 集成测试开关：只有 {@code PMS_IT_DB=true} 时才执行需要真实 PostgreSQL 的用例。
 *
 * <p><b>为什么不用 {@code @EnabledIfEnvironmentVariable}：</b>JUnit 5 的 {@code @EnabledIf*}
 * 条件注解<b>不会被继承</b>。把它放在基类上看着很自然，实际却不生效——子类照跑不误，
 * 在没有数据库的机器上就是一连串连接失败，而不是"跳过"。这个坑是本轮改造时真实踩到的
 * （无库环境下 13 个用例仍然执行并连上了本机数据库），所以改成自定义 {@link ExecutionCondition}，
 * 通过基类上的 {@code @ExtendWith} 注册——{@code @ExtendWith} 是可继承的。
 *
 * <p>语义与原来一致：**跳过而不是伪造通过**。跳过会出现在 surefire 的 skipped 计数里，
 * CI 的"零跳过断言"则保证在 CI 上它们必须真跑。
 */
public class IntegrationDbEnabledCondition implements ExecutionCondition {

    private static final String FLAG = "PMS_IT_DB";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        String value = System.getenv(FLAG);
        if (value != null && "true".equalsIgnoreCase(value.trim())) {
            return ConditionEvaluationResult.enabled(FLAG + "=true，执行需要真实 PostgreSQL 的用例");
        }
        return ConditionEvaluationResult.disabled(
                FLAG + " 未设为 true：跳过需要真实 PostgreSQL 的用例（跳过而非伪造通过）");
    }
}
