package com.omnimerchant.common.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT Claims 结构与安全校验测试（阶段 10）。
 *
 * <p>覆盖：合法 Token、签名篡改、过期、Issuer 错误、Audience 错误、
 * 缺少 tenantIds、platformAdmin 伪造、tokenVersion 透传。
 */
class JwtUtilTest {

    private static final String SECRET = "01234567890123456789012345678901";

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 86_400_000);

    @Test
    void shouldGeneratePlatformAdminClaims() {
        var token = jwtUtil.generatePlatformAdminToken("admin@example.com");

        var principal = jwtUtil.parsePrincipal(token);

        assertThat(principal.subject()).isEqualTo("admin@example.com");
        assertThat(principal.role()).isEqualTo("ADMIN");
        assertThat(principal.roles()).contains("PLATFORM_ADMIN");
        assertThat(principal.platformAdmin()).isTrue();
        assertThat(principal.canAccessTenant(99L)).isTrue();
    }

    @Test
    void shouldBindTenantMembershipClaims() {
        var token = jwtUtil.generateToken("owner@example.com", "TENANT_USER", List.of(1L, 2L), false);

        var principal = jwtUtil.parsePrincipal(token);

        assertThat(principal.platformAdmin()).isFalse();
        assertThat(principal.tenantIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(principal.canAccessTenant(1L)).isTrue();
        assertThat(principal.canAccessTenant(3L)).isFalse();
    }

    @Test
    void shouldCarryTenantPermissionsAndTokenVersion() {
        var token = jwtUtil.generateAccessToken("agent@example.com", 42L, "SUPPORT_AGENT",
                Set.of("SUPPORT_AGENT"),
                Map.of(1001L, Set.of("SUPPORT_AGENT")),
                Map.of(1001L, Set.of("inbox:reply", "ticket:resolve")),
                false, 7);

        var principal = jwtUtil.parsePrincipal(token);

        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.tokenVersion()).isEqualTo(7);
        assertThat(principal.hasPermission(1001L, "inbox:reply")).isTrue();
        assertThat(principal.hasPermission(1001L, "action:approve")).isFalse();
        assertThat(principal.hasPermission(1002L, "inbox:reply")).isFalse();
    }

    @Test
    void shouldRejectTamperedSignature() {
        var token = jwtUtil.generatePlatformAdminToken("admin@example.com");
        var tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> jwtUtil.parsePrincipal(tampered))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldRejectExpiredToken() {
        var expired = new JwtUtil(SECRET, -1_000).generatePlatformAdminToken("admin@example.com");

        assertThatThrownBy(() -> jwtUtil.parsePrincipal(expired))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldRejectWrongIssuer() {
        var token = signedWithClaims("admin@example.com", Map.of(
                "iss", "evil-issuer",
                "aud", JwtUtil.DEFAULT_ADMIN_AUDIENCE,
                "platformAdmin", true));

        assertThatThrownBy(() -> jwtUtil.parsePrincipal(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issuer or audience");
    }

    @Test
    void shouldRejectWrongAudience() {
        var token = signedWithClaims("admin@example.com", Map.of(
                "iss", JwtUtil.DEFAULT_ADMIN_ISSUER,
                "aud", "some-other-api",
                "platformAdmin", true));

        assertThatThrownBy(() -> jwtUtil.parsePrincipal(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issuer or audience");
    }

    @Test
    void shouldTreatMissingTenantIdsAsNoAccess() {
        var token = jwtUtil.generateAccessToken("limited@example.com", 5L, "TENANT_USER",
                Set.of("TENANT_USER"), Map.of(), Map.of(), false, 1);

        var principal = jwtUtil.parsePrincipal(token);

        assertThat(principal.tenantIds()).isEmpty();
        assertThat(principal.canAccessTenant(1001L)).isFalse();
    }

    @Test
    void shouldNotElevateWhenPlatformAdminClaimAbsent() {
        // 普通租户 Token 没有 platformAdmin=true，不能跨租户访问
        var token = jwtUtil.generateAccessToken("owner@example.com", 9L, "TENANT_ADMIN",
                Set.of("TENANT_ADMIN"), Map.of(1001L, Set.of("TENANT_ADMIN")),
                Map.of(1001L, Set.of("tenant:manage")), false, 1);

        var principal = jwtUtil.parsePrincipal(token);

        assertThat(principal.platformAdmin()).isFalse();
        assertThat(principal.canAccessTenant(1002L)).isFalse();
        assertThat(principal.hasPermission(1002L, "tenant:manage")).isFalse();
    }

    private String signedWithClaims(String subject, Map<String, Object> claims) {
        var now = new Date();
        var builder = Jwts.builder()
                .subject(subject)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)));
        claims.forEach(builder::claim);
        return builder.compact();
    }
}
