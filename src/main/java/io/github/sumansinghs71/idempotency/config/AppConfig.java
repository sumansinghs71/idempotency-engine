package io.github.sumansinghs71.idempotency.config;

import io.github.sumansinghs71.idempotency.interceptor.IdempotencyInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties({IdempotencyProperties.class, PspProperties.class})
public class AppConfig implements WebMvcConfigurer {

    private final IdempotencyInterceptor interceptor;

    public AppConfig(IdempotencyInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/charges");
    }
}
