package com.omnimerchant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Widget 接口端到端安全测试（阶段 11）。
 *
 * <p>覆盖：会话创建、Widget 令牌隔离（不能访问后台）、无/坏/过期令牌 401、
 * tenantCode / conversationUuid 不匹配 403、跨租户 403、Widget Token 不能被后台过滤链接受。
 *
 * <p>需要 MySQL 可用。所有断言都在认证 / 校验阶段完成（不进入模型调用），
 * 因此不会真实调用 DeepSeek。
 */
@EnabledIf("infraAvailable")
@SpringBootTest(properties = {
        "admin.email=widget-it-admin@example.com",
        "admin.password=widget-it-admin-pw-0123",
        "admin.jwt-secret=01234567890123456789012345678901",
        "omnimerchant.helpdesk.projection-scheduler-enabled=false",
        // 测试会多次创建会话，需放宽公共入口的滥用频率限制（生产默认 20/10min 不变）
        "app.widget.session-rate-limit-per-tenant=100000"
})
@AutoConfigureMockMvc
class WidgetApiTest {

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

    @Test
    void sessionWithUnknownTenantReturnsBusinessError() throws Exception {
        var result = mockMvc.perform(post("/api/widget/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"NOPE-404\"}"))
                .andExpect(status().isOk())
                .andReturn();
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        // 业务错误走 HTTP 200 + 业务码（GlobalExceptionHandler 约定）
        assertThat(body.get("code").asText()).isNotEqualTo("200");
    }

    @Test
    void sessionWithRealTenantCreatesConversationAndToken() throws Exception {
        var session = createSession("OM-1001");
        assertThat(session.get("tenantId").asLong()).isEqualTo(1001L);
        assertThat(session.get("conversationUuid").asText()).isNotBlank();
        assertThat(session.get("customerSessionToken").asText()).isNotBlank();
        assertThat(session.get("storeName").asText()).isNotBlank();
    }

    @Test
    void chatWithoutWidgetTokenReturns401() throws Exception {
        var session = createSession("OM-1001");
        mockMvc.perform(post("/api/widget/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody("OM-1001", session.get("conversationUuid").asText(), "你好")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chatWithInvalidWidgetTokenReturns401() throws Exception {
        var session = createSession("OM-1001");
        mockMvc.perform(post("/api/widget/chat/stream")
                        .header("Authorization", "Bearer not-a-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody("OM-1001", session.get("conversationUuid").asText(), "你好")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chatWithAdminTokenReturns401() throws Exception {
        // 后台 admin 令牌不能冒充买家
        var adminToken = adminToken();
        var session = createSession("OM-1001");
        mockMvc.perform(post("/api/widget/chat/stream")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody("OM-1001", session.get("conversationUuid").asText(), "你好")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chatWithConversationMismatchReturns403() throws Exception {
        var session = createSession("OM-1001");
        var token = session.get("customerSessionToken").asText();
        mockMvc.perform(post("/api/widget/chat/stream")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody("OM-1001", "some-other-conversation-uuid", "你好")))
                .andExpect(status().isForbidden());
    }

    @Test
    void chatWithTenantCodeMismatchReturns403() throws Exception {
        var session = createSession("OM-1001");
        var token = session.get("customerSessionToken").asText();
        mockMvc.perform(post("/api/widget/chat/stream")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody("OM-1002", session.get("conversationUuid").asText(), "你好")))
                .andExpect(status().isForbidden());
    }

    @Test
    void widgetTokenCannotAccessBackendApis() throws Exception {
        var session = createSession("OM-1001");
        var token = session.get("customerSessionToken").asText();

        // 后台接口：Widget 令牌不是后台 JwtPrincipal，必须 401
        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", "1001"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", "1001"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/tenants").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void twoTenantsCreateSessionsUnderTheirOwnTenant() throws Exception {
        var s1 = createSession("OM-1001");
        var s2 = createSession("OM-1002");

        assertThat(s1.get("tenantId").asLong()).isEqualTo(1001L);
        assertThat(s2.get("tenantId").asLong()).isEqualTo(1002L);
        assertThat(s1.get("conversationUuid").asText())
                .isNotEqualTo(s2.get("conversationUuid").asText());
    }

    @Test
    void tenant1001TokenCannotUse1002Conversation() throws Exception {
        var s1 = createSession("OM-1001");
        var s2 = createSession("OM-1002");
        // 用 1001 的令牌 + 伪造 1002 的 tenantCode/conversationUuid
        mockMvc.perform(post("/api/widget/chat/stream")
                        .header("Authorization", "Bearer " + s1.get("customerSessionToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody("OM-1002", s2.get("conversationUuid").asText(), "你好")))
                .andExpect(status().isForbidden());
    }

    private com.fasterxml.jackson.databind.JsonNode createSession(String tenantCode) throws Exception {
        var result = mockMvc.perform(post("/api/widget/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"" + tenantCode + "\",\"customerEmail\":\"buyer@example.com\"}"))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(tree.get("code").asText()).isEqualTo("200");
        return tree.get("data");
    }

    private String adminToken() throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"widget-it-admin@example.com\",\"password\":\"widget-it-admin-pw-0123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
    }

    private String chatBody(String tenantCode, String conversationUuid, String message) {
        return "{\"tenantCode\":\"" + tenantCode + "\",\"conversationUuid\":\"" + conversationUuid
                + "\",\"message\":\"" + message + "\",\"intent\":\"GENERAL\"}";
    }
}
