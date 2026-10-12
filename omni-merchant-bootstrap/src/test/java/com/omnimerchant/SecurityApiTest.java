package com.omnimerchant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.admin.service.IdentityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring Security API 端到端测试（阶段 10）。
 *
 * <p>启动完整 Spring 上下文（MySQL / Redis / PostgreSQL 均需可用）：
 * 验证登录签发 JWT、无令牌 401、跨租户 403、平台管理员路径授权。
 * 基础设施不可用时整类跳过。
 */
@EnabledIf("infraAvailable")
@SpringBootTest(properties = {
        "admin.email=it-admin@example.com",
        "admin.password=it-admin-password-0123",
        "admin.jwt-secret=01234567890123456789012345678901",
        "omnimerchant.helpdesk.projection-scheduler-enabled=false"
})
@AutoConfigureMockMvc
class SecurityApiTest {

    static boolean infraAvailable() {
        try (Connection ignored = java.sql.DriverManager.getConnection(
                "jdbc:mysql://localhost:3306/mini-omni-merchant", "root", "123456")) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    IdentityService identityService;

    @Test
    void loginWithValidCredentialsReturnsTokens() throws Exception {
        var body = login("it-admin@example.com", "it-admin-password-0123");

        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("refreshToken").asText()).isNotBlank();
        assertThat(body.get("platformAdmin").asBoolean()).isTrue();
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"it-admin@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void businessApiWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/orders").header("X-Tenant-Id", "1001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void platformAdminCanListTenants() throws Exception {
        var token = login("it-admin@example.com", "it-admin-password-0123").get("accessToken").asText();

        mockMvc.perform(get("/api/tenants")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void tenantUserCannotAccessPlatformAdminPath() throws Exception {
        var email = "it-tenant-agent@example.com";
        var token = tenantUserToken(email, 1001L);

        mockMvc.perform(get("/api/tenants")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantUserCannotAccessOtherTenantViaHeader() throws Exception {
        var token = tenantUserToken("it-tenant-agent@example.com", 1001L);

        // 用户只属于 1001，请求 1002：TenantInterceptor 必须 403
        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", "1002"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantUserCanAccessOwnTenant() throws Exception {
        var token = tenantUserToken("it-tenant-agent@example.com", 1001L);

        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", "1001"))
                .andExpect(status().isOk());
    }

    /** 保证存在一个属于租户 1001 的 SUPPORT_AGENT，并登录取得令牌。 */
    private String tenantUserToken(String email, Long tenantId) throws Exception {
        Integer existing = jdbcCount(email);
        if (existing == null || existing == 0) {
            identityService.createUser(new IdentityService.CreateUserCommand(
                    email, "IT 客服", "it-password-012345", false,
                    List.of(new IdentityService.MembershipCommand(tenantId, "SUPPORT_AGENT"))), null);
        }
        var tokens = identityService.login(email, "it-password-012345");
        return tokens.accessToken();
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private Integer jdbcCount(String email) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE email = ?",
                Integer.class, email);
    }

    private JsonNode login(String email, String password) throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        return tree.get("data");
    }
}
