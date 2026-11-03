package cn.sec.hn.openbackupsaas.shared.config;

import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import cn.sec.hn.openbackupsaas.shared.mybatis.DateRangeTypeHandler;
import cn.sec.hn.openbackupsaas.shared.mybatis.UuidTypeHandler;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * MyBatis 全局配置（代码级，与 application-*.properties 的分开维护）。
 *
 * <p>当前只注册两个必要的类型处理器：
 * <ul>
 *   <li>{@link UuidTypeHandler}：MyBatis 3.5.19 已移除内置 UUID 处理器，
 *       不注册则所有 uuid 列的映射与参数绑定都会失败（S1-02）。</li>
 *   <li>{@link DateRangeTypeHandler}：daterange ↔ {@link StayRange}（S1-05）。
 *       区间必须作为**一个列**存在，它是排斥约束的作用对象，拆成两个日期列会让约束失效。</li>
 * </ul>
 * 后续如需自定义枚举映射、日志实现等，统一加在这里，不在各 Mapper XML 里零散指定。
 */
@Configuration
public class MyBatisConfig {

    @Bean
    ConfigurationCustomizer typeHandlerCustomizer() {
        return configuration -> {
            var registry = configuration.getTypeHandlerRegistry();
            registry.register(UUID.class, new UuidTypeHandler());
            registry.register(StayRange.class, new DateRangeTypeHandler());
        };
    }
}
