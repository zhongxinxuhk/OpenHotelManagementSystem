package cn.sec.hn.openbackupsaas.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 连真实 PostgreSQL 的集成测试基类（S1-13）。
 *
 * <p>三件事统一在这里做，避免每个测试类各写一遍：
 * <ol>
 *   <li>固定 {@code integration} 档案与 {@code PMS_IT_DB=true} 开关——没设开关就整类跳过，
 *       跳过而不是伪造通过；</li>
 *   <li>每个测试方法前清空数据库，并在清空后<b>断言全库为空</b>（见 {@link DatabaseCleaner}）；</li>
 *   <li>不提供任何假实现——需要桩的只有不连库的 {@code test} 档案。</li>
 * </ol>
 *
 * <p><b>为什么不再用 {@code @Sql("/db/integration-cleanup.sql")}：</b>那份脚本靠人手维护
 * 删除清单，加表忘一行就会让整批用例拿脏库跑，而且错误信息只说"第 N 条语句失败"。
 * 现在由 {@link DatabaseCleaner} 按外键拓扑序自动清理并点名报告，新增表无需改任何脚本。
 */
@SpringBootTest
@ActiveProfiles("integration")
// 条件必须用可继承的 @ExtendWith 注册：JUnit 5 的 @EnabledIf* 不会被继承，
// 放在基类上等于没写（见 IntegrationDbEnabledCondition 的说明）。
@ExtendWith(IntegrationDbEnabledCondition.class)
public abstract class PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private DatabaseCleaner cleaner;

    @BeforeEach
    void cleanDatabaseBeforeEachTest() {
        if (cleaner == null) {
            cleaner = new DatabaseCleaner(jdbcTemplate);
        }
        cleaner.clean();
    }
}
