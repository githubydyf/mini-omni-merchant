package com.omnimerchant.config;

import com.omnimerchant.common.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 配置：从环境变量 / application.yml 读取密钥并构建 {@link JwtUtil}。
 *
 * <p><b>安全约束</b>：{@code JWT_SECRET} 必须来自环境变量（或本地开发配置），
 * 不允许在代码 / SQL / Git 仓库中写死真实密钥。缺失时启动即失败，避免以空密钥运行。
 */
@Configuration
public class AdminConfig {

    @Value("${admin.jwt-secret:}")
    private String jwtSecret;

    @Value("${admin.jwt-expiration-ms:86400000}")
    private long jwtExpirationMs;

    @Value("${admin.jwt-previous-secret:}")
    private String previousJwtSecret;

    @Value("${admin.issuer:omnimerchant-admin}")
    private String adminIssuer;

    @Value("${admin.audience:omnimerchant-admin-api}")
    private String adminAudience;

    @Bean
    public JwtUtil jwtUtil() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "admin.jwt-secret 必须通过环境变量 JWT_SECRET 配置（长度至少 32 字节）");
        }
        return new JwtUtil(jwtSecret, previousJwtSecret, jwtExpirationMs,
                adminIssuer, adminAudience);
    }
}
