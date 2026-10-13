package com.omnimerchant.agent.support;

import com.omnimerchant.common.util.JwtUtil.JwtPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录操作者身份。
 *
 * <p>替代开发阶段的 {@code DevActor}：操作人 ID 必须来自已验证的 JWT principal
 * （{@code SecurityContextHolder} 中的 {@link JwtPrincipal}），<b>禁止</b>使用固定开发人员 ID。
 *
 * <p>请求体中的 {@code agentId} / {@code actorId} 不能覆盖真实登录操作人，
 * 因此这里只读取安全上下文，不接受任何请求参数。
 */
public final class CurrentActor {

    private CurrentActor() {
    }

    /** 当前登录用户 ID；未认证时返回 null。 */
    public static Long id() {
        var principal = principal();
        return principal == null ? null : principal.userId();
    }

    /** 当前登录用户名（JWT subject）；未认证时返回 null。 */
    public static String subject() {
        var principal = principal();
        return principal == null ? null : principal.subject();
    }

    /** 是否已认证。 */
    public static boolean authenticated() {
        return principal() != null;
    }

    public static JwtPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof JwtPrincipal principal) {
            return principal;
        }
        return null;
    }
}
