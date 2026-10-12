package com.omnimerchant.security;

import com.omnimerchant.common.util.JwtUtil.JwtPrincipal;
import com.omnimerchant.tenant.context.TenantContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 租户 + RBAC 授权判定组件。
 *
 * <p>复现自参考项目 {@code security/TenantAuthorization}，供 {@code @PreAuthorize}
 * 表达式以 {@code @tenantAuthorization} 引用。
 *
 * <p><b>权限来源</b>：已验证的 JWT principal（{@code SecurityContextHolder}）+
 * 当前租户上下文（{@code TenantContextHolder}）+ 数据库角色成员关系（登录时已汇总进
 * JWT 的 {@code tenantPermissions}）。<b>绝不</b>从客户端请求体读取角色或权限。
 */
@Component("tenantAuthorization")
public class TenantAuthorization {

    /** 当前租户下是否拥有指定权限（平台管理员默认通过）。 */
    public boolean hasPermission(String permission) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof JwtPrincipal principal)) {
            return false;
        }
        return principal.hasPermission(TenantContextHolder.get(), permission);
    }

    /** 是否为平台管理员。 */
    public boolean isPlatformAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof JwtPrincipal principal
                && principal.platformAdmin();
    }

    /** 当前登录用户 ID（未登录返回 null）。 */
    public Long currentUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof JwtPrincipal principal)) {
            return null;
        }
        return principal.userId();
    }
}
