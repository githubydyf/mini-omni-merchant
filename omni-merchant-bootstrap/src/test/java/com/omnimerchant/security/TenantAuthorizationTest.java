package com.omnimerchant.security;

import com.omnimerchant.common.util.JwtUtil.JwtPrincipal;
import com.omnimerchant.tenant.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RBAC 授权测试（阶段 10）：权限来自「已验证 JWT + 当前租户 + membership 角色权限」。
 */
class TenantAuthorizationTest {

    private final TenantAuthorization authorization = new TenantAuthorization();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void shouldDenyWhenNotAuthenticated() {
        TenantContextHolder.set(1001L);

        assertThat(authorization.hasPermission("inbox:reply")).isFalse();
        assertThat(authorization.isPlatformAdmin()).isFalse();
        assertThat(authorization.currentUserId()).isNull();
    }

    @Test
    void shouldHonourTenantScopedPermissions() {
        authenticate(principal(1001L, false, Map.of(1001L, Set.of("inbox:reply"))));
        TenantContextHolder.set(1001L);

        assertThat(authorization.hasPermission("inbox:reply")).isTrue();
        // 同一用户切换到未授权权限时拒绝
        assertThat(authorization.hasPermission("action:approve")).isFalse();
    }

    @Test
    void shouldNotLeakPermissionsAcrossTenants() {
        // 用户在 1001 有 inbox:reply，但在 1002 没有任何权限
        authenticate(principal(1001L, false, Map.of(1001L, Set.of("inbox:reply"))));

        TenantContextHolder.set(1002L);
        assertThat(authorization.hasPermission("inbox:reply")).isFalse();

        TenantContextHolder.set(1001L);
        assertThat(authorization.hasPermission("inbox:reply")).isTrue();
    }

    @Test
    void platformAdminBypassesTenantPermissions() {
        var principal = new JwtPrincipal("admin@example.com", "ADMIN", Set.of(), true);
        authenticate(principal);
        TenantContextHolder.set(1001L);

        assertThat(authorization.isPlatformAdmin()).isTrue();
        assertThat(authorization.hasPermission("action:approve")).isTrue();
    }

    @Test
    void shouldReturnCurrentUserId() {
        authenticate(new JwtPrincipal("agent@example.com", "SUPPORT_AGENT", Set.of(1001L),
                false, 77L, Set.of("SUPPORT_AGENT"), Map.of(), Map.of(), "jti-1", 3));

        assertThat(authorization.currentUserId()).isEqualTo(77L);
    }

    private void authenticate(JwtPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, Set.of()));
    }

    private JwtPrincipal principal(Long tenantId, boolean platformAdmin,
                                   Map<Long, Set<String>> permissions) {
        return new JwtPrincipal("owner@example.com", "SUPPORT_AGENT", Set.of(tenantId),
                platformAdmin, 5L, Set.of("SUPPORT_AGENT"), Map.of(), permissions, "jti", 1);
    }
}
