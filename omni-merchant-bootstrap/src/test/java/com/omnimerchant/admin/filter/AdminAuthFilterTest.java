package com.omnimerchant.admin.filter;

import com.omnimerchant.common.constant.Constants;
import com.omnimerchant.common.util.JwtUtil;
import com.omnimerchant.common.util.JwtUtil.JwtPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdminAuthFilter 测试（阶段 10）：无令牌 401、平台路径 403、合法令牌放行。
 */
class AdminAuthFilterTest {

    private final JwtUtil jwtUtil = new JwtUtil("01234567890123456789012345678901", 86_400_000);
    private final AdminAuthFilter filter = new AdminAuthFilter(jwtUtil);

    @Test
    void shouldRejectChatWithoutBearerToken() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/chat/stream");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void shouldRejectObservabilityWithoutBearerToken() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/observability/traces");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void shouldRejectInvalidBearerToken() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/chat/stream");
        request.addHeader("Authorization", "Bearer not-a-jwt");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void shouldAttachPrincipalForProtectedPath() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/chat/stream");
        request.addHeader("Authorization", "Bearer " +
                jwtUtil.generateToken("owner@example.com", "TENANT_USER", List.of(1001L), false));
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(Constants.ATTR_AUTH_PRINCIPAL))
                .isInstanceOfSatisfying(JwtPrincipal.class, principal -> {
                    assertThat(principal.subject()).isEqualTo("owner@example.com");
                    assertThat(principal.canAccessTenant(1001L)).isTrue();
                });
    }

    @Test
    void shouldForbidTenantUserFromPlatformAdminPath() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/tenants");
        request.addHeader("Authorization", "Bearer " +
                jwtUtil.generateToken("owner@example.com", "TENANT_USER", List.of(1001L), false));
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void shouldAllowPlatformAdminPathForPlatformAdmin() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/tenants");
        request.addHeader("Authorization", "Bearer " +
                jwtUtil.generatePlatformAdminToken("admin@example.com"));
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldAllowPublicLoginWithoutToken() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
