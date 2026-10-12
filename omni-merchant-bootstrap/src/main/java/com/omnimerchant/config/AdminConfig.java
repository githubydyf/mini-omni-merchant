package com.omnimerchant.config;

import com.omnimerchant.common.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 配置：从环境变量 / application.yml 读取密钥并构建 {@link JwtUtil}。
 *
 * <p><b>安全约束</b>：
 * <ul>
 *   <li>生产环境必须通过环境变量 {@code JWT_SECRET} 提供真实密钥（至少 32 字节）；
 *       {@code prod} profile 下缺失即启动失败（见 {@code application-prod.yml}）。</li>
 *   <li>本地开发直接运行时，application.yml 提供了一个<b>明显不安全的占位密钥</b>，
 *       便于开箱即用；检测到该占位值时会打印警告，提醒生产务必覆盖。</li>
 *   <li>密钥同样可以在 IDEA 运行配置里通过环境变量覆盖。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class AdminConfig {

    /** 与 application.yml 中本地开发占位密钥保持一致（仅用于检测并告警）。 */
    private static final String DEV_PLACEHOLDER_SECRET =
            "omnimerchant-dev-only-insecure-secret-change-me-0123";

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

    @Value("${admin.widget-issuer:omnimerchant-widget}")
    private String widgetIssuer;

    @Value("${admin.widget-audience:omnimerchant-widget-api}")
    private String widgetAudience;

    @Bean
    public JwtUtil jwtUtil() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "admin.jwt-secret 必须通过环境变量 JWT_SECRET 配置（至少 32 字节）");
        }
        if (DEV_PLACEHOLDER_SECRET.equals(jwtSecret)) {
            log.warn("当前使用本地开发占位 JWT 密钥（不安全）。生产环境请设置环境变量 JWT_SECRET 覆盖。");
        }
        return new JwtUtil(jwtSecret, previousJwtSecret, jwtExpirationMs,
                adminIssuer, adminAudience, widgetIssuer, widgetAudience);
    }
}
