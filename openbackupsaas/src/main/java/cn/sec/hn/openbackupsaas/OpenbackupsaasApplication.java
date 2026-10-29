package cn.sec.hn.openbackupsaas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * PMS 应用入口（模块化单体，单 Maven 工程，按领域分包）。
 *
 * <p>排除 {@link UserDetailsServiceAutoConfiguration}：它会在启动时生成一个随机密码的内存用户，
 * 容易被误读为"系统已有登录能力"。认证在 S1-03 按 ADR-008 实现，届时一并恢复。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class OpenbackupsaasApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenbackupsaasApplication.class, args);
    }

}
