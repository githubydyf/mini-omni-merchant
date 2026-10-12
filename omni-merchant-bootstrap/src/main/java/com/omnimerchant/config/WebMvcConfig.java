package com.omnimerchant.config;

import com.omnimerchant.tenant.interceptor.TenantInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册 {@link TenantInterceptor}。
 *
 * <p>拦 {@code /api/**}，排除不需要租户上下文的路径（认证、租户管理、平台用户、
 * RAG 运维、Webhook 等）。真正的租户访问校验在拦截器内完成。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/health",
                        "/api/auth/**",
                        "/api/admin/**",
                        "/api/tenants/**",
                        "/api/rag/**",
                        "/api/widget/**",
                        "/api/webhooks/**",
                        "/api/public/**");
    }
}
