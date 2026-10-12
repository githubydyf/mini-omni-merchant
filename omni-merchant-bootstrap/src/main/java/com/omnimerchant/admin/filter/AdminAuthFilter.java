package com.omnimerchant.admin.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.admin.service.IdentityService;
import com.omnimerchant.common.constant.Constants;
import com.omnimerchant.common.dto.R;
import com.omnimerchant.common.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 后台 Bearer JWT 认证过滤器。
 *
 * <p>复现自参考项目 {@code admin/filter/AdminAuthFilter}，职责：
 * <ol>
 *   <li>读取 {@code Authorization} 头，提取 Bearer Token；</li>
 *   <li>用 {@link JwtUtil} 验签、校验 issuer / audience / 过期；</li>
 *   <li>检查 Access Token 吊销状态（{@code revoked_access_token}）；</li>
 *   <li>检查 {@code tokenVersion} 是否仍然有效（用户停用 / membership 变更后失效）；</li>
 *   <li>构建 {@code JwtPrincipal}，放入 request attribute 与 {@code SecurityContextHolder}；</li>
 *   <li>区分认证失败（401）与授权失败（403）：平台级路径对非平台管理员返回 403。</li>
 * </ol>
 */
@Slf4j
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/health",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/admin/login",
            "/api/admin/refresh"
    );

    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/chat", "/api/test", "/api/tenants", "/api/knowledge",
            "/api/conversations", "/api/billing", "/api/customers", "/api/orders",
            "/api/products", "/api/escalations", "/api/tickets", "/api/tool-calls",
            "/api/dashboard", "/api/integrations", "/api/evals", "/api/observability",
            "/api/rag", "/api/admin", "/api/auth", "/api/channels", "/api/inbox",
            "/api/sla", "/api/macros", "/api/actions", "/api/qa", "/api/operations",
            "/api/audit", "/api/sre", "/api/agent", "/api/security", "/api/multilingual",
            "/api/system"
    );

    private static final Set<String> PLATFORM_ADMIN_PATHS = Set.of(
            "/api/tenants",
            "/api/admin/users",
            "/api/admin/roles"
    );

    private final JwtUtil jwtUtil;
    private final IdentityService identityService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdminAuthFilter(JwtUtil jwtUtil) {
        this(jwtUtil, null);
    }

    public AdminAuthFilter(JwtUtil jwtUtil, IdentityService identityService) {
        this.jwtUtil = jwtUtil;
        this.identityService = identityService;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // SSE 请求在 servlet async dispatch 阶段会再次经过过滤器链。
        // 这里返回 false，确保异步分发时同样重建无状态 SecurityContext。
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }
        if (!isProtected(path)) {
            chain.doFilter(request, response);
            return;
        }

        var authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeUnauthorized(request, response, "缺少认证令牌");
            return;
        }

        JwtUtil.JwtPrincipal principal;
        try {
            principal = jwtUtil.parsePrincipal(authHeader.substring(7));
            if (identityService != null && (identityService.isAccessTokenRevoked(principal.tokenId())
                    || !identityService.isTokenVersionCurrent(principal))) {
                writeUnauthorized(request, response, "令牌已吊销，请重新登录");
                return;
            }
            if (PLATFORM_ADMIN_PATHS.stream().anyMatch(path::startsWith) && !principal.platformAdmin()) {
                writeForbidden(request, response, "仅平台管理员可访问该资源");
                return;
            }
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(request, response, "令牌无效或已过期");
            return;
        }
        request.setAttribute(Constants.ATTR_AUTH_PRINCIPAL, principal);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, authorities(principal));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }

    /**
     * 把 JWT 中声明的角色 / 权限映射成 Spring Security authorities。
     *
     * <p>角色映射为 {@code ROLE_xxx}；租户权限按权限键原样加入，供 {@code @PreAuthorize}
     * 表达式使用。
     */
    private Set<SimpleGrantedAuthority> authorities(JwtUtil.JwtPrincipal principal) {
        var values = new LinkedHashSet<SimpleGrantedAuthority>();
        principal.roles().forEach(role -> values.add(new SimpleGrantedAuthority("ROLE_" + role)));
        principal.tenantPermissions().values().forEach(permissions ->
                permissions.forEach(permission -> values.add(new SimpleGrantedAuthority(permission))));
        if (principal.platformAdmin()) {
            values.add(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
        }
        return values;
    }

    private boolean isPublic(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    private boolean isProtected(String path) {
        return PROTECTED_PATHS.stream().anyMatch(path::startsWith);
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        log.warn("认证失败: status=401, method={}, path={}, message={}",
                request.getMethod(), request.getRequestURI(), message);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(R.fail("401", message)));
    }

    private void writeForbidden(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        log.warn("授权失败: status=403, method={}, path={}, message={}",
                request.getMethod(), request.getRequestURI(), message);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(R.fail("403", message)));
    }
}
