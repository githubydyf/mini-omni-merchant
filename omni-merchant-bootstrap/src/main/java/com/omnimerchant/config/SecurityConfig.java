package com.omnimerchant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.admin.filter.AdminAuthFilter;
import com.omnimerchant.admin.service.IdentityService;
import com.omnimerchant.common.dto.R;
import com.omnimerchant.common.util.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置（无状态 Bearer JWT）。
 *
 * <p>复现自参考项目 {@code config/SecurityConfig}：
 * <ul>
 *   <li>{@link BCryptPasswordEncoder}(12) 作为密码编码器；</li>
 *   <li>{@code @EnableMethodSecurity} 启用 {@code @PreAuthorize}；</li>
 *   <li>无状态会话（STATELESS），禁用默认 Form Login 与 HTTP Basic；</li>
 *   <li>只有明确白名单接口公开，其余 {@code /api/**} 一律要求认证；</li>
 *   <li>认证失败 401、授权失败 403，明确区分。</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** 公开接口白名单：登录、刷新、健康检查。 */
    private static final String[] PUBLIC_MATCHERS = {
            "/api/health",
            "/actuator/health",
            "/actuator/info",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/admin/login",
            "/api/admin/refresh"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AdminAuthFilter adminAuthFilter(JwtUtil jwtUtil, IdentityService identityService) {
        return new AdminAuthFilter(jwtUtil, identityService);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AdminAuthFilter adminAuthFilter,
                                                   ObjectMapper objectMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_MATCHERS).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    R.fail("401", "缺少认证令牌")));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    R.fail("403", "无权执行该操作")));
                        }))
                .addFilterBefore(adminAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
