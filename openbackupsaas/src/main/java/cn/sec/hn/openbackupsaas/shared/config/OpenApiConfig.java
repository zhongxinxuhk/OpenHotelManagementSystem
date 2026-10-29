package cn.sec.hn.openbackupsaas.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档元信息。
 *
 * <p>生成的文档不能代替契约审查：幂等键、错误码、冲突与待核实语义在 S0-09/S1-11 冻结，
 * 届时以 contracts/openapi 为准，不以后端注解反推业务规则。
 */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfig {

    @Bean
    OpenAPI pmsOpenApi(@Value("${info.app.version:dev}") String version) {
        return new OpenAPI().info(new Info()
                .title("PMS API")
                .version(version)
                .description("企业级酒店 PMS 接口。库存与订单事实以 PostgreSQL 为准，"
                        + "写操作需携带幂等键（S1-07/S1-11 冻结字段名）。"));
    }
}
