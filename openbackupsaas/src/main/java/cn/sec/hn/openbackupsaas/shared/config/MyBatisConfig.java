package cn.sec.hn.openbackupsaas.shared.config;

import cn.sec.hn.openbackupsaas.shared.mybatis.UuidTypeHandler;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * MyBatis 全局配置（代码级，与 application-*.properties 的分开维护）。
 *
 * <p>当前只做一件必要的事：注册 {@link UuidTypeHandler}。
 * MyBatis 3.5.19 已移除内置 UUID 处理器，不注册则所有 uuid 列的映射与参数绑定都会失败。
 * 后续如需自定义枚举映射、日志实现等，统一加在这里，不在各 Mapper XML 里零散指定。
 */
@Configuration
public class MyBatisConfig {

    @Bean
    ConfigurationCustomizer uuidTypeHandlerCustomizer() {
        return configuration -> configuration.getTypeHandlerRegistry()
                .register(UUID.class, new UuidTypeHandler());
    }
}
