package cn.sec.hn.openbackupsaas;

import cn.sec.hn.openbackupsaas.shared.config.SecurityConfig;
import cn.sec.hn.openbackupsaas.shared.web.GlobalExceptionHandler;
import cn.sec.hn.openbackupsaas.shared.web.RequestIdFilter;
import cn.sec.hn.openbackupsaas.support.NoDatabaseStubConfig;
import cn.sec.hn.openbackupsaas.tenant.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文冒烟测试：只证明"装配能起来"。
 *
 * <p>使用 test 档案会排除 DataSource / Flyway / MyBatis 自动配置，
 * 因此本测试**不验证**任何数据库行为。库存占用、排斥约束、幂等与并发必须用真实 PostgreSQL
 * 在 S1-05、S1-13 验证（见 docs/verification/ 与测试矩阵 T01—T09）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(NoDatabaseStubConfig.class)
class ApplicationContextTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithBaselineBeans() {
        assertNotNull(context.getBean(RequestIdFilter.class), "请求关联 ID 过滤器必须注册");
        assertNotNull(context.getBean(GlobalExceptionHandler.class), "统一错误响应必须注册");
        assertNotNull(context.getBean(SecurityConfig.class), "安全基线必须注册");
        assertNotNull(context.getBean(OrganizationService.class), "组织服务必须注册");
    }

    @Test
    void securityChainIsPresent() {
        assertTrue(context.containsBean("apiFilterChain"), "必须存在显式的安全过滤链，而不是依赖默认配置");
    }

}
