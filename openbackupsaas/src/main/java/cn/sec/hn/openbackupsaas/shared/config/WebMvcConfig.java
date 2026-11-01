package cn.sec.hn.openbackupsaas.shared.config;

import cn.sec.hn.openbackupsaas.identity.web.PasswordChangeRequiredInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 拦截器注册。
 *
 * <p>目前只有一条：待改密账号的限制（V13，见 {@link PasswordChangeRequiredInterceptor}）。
 *
 * <p>为什么拦截器在这里 {@code new} 而不是给拦截器加 {@code @Component}：
 * 与 {@code SecurityConfig} 里构造认证过滤器的理由一致——组件的注册方式只有一个地方可查，
 * 不必再去想"这个 Bean 是不是被别的机制又挂了一遍"。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PasswordChangeRequiredInterceptor());
        // 平台平面的首改限制是**另一份清单**（路径与主体类型都不同），
        // 不与员工侧共用一个实例，防止两份放行清单被写串。
        registry.addInterceptor(new cn.sec.hn.openbackupsaas.platform.web.PlatformPasswordChangeRequiredInterceptor())
                .addPathPatterns("/api/v1/platform/**");
    }
}
