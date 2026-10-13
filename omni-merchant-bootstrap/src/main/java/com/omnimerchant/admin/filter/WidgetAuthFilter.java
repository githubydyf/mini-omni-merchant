package com.omnimerchant.admin.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.common.constant.Constants;
import com.omnimerchant.common.dto.R;
import com.omnimerchant.common.util.JwtUtil;
import com.omnimerchant.common.util.JwtUtil.WidgetCustomerPrincipal;
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
import java.util.Set;

/**
 * Widget 买家认证过滤器（独立于后台 {@link AdminAuthFilter}）。
 *
 * <p>处理买家聊天流和增量消息查询：
 * <ul>
 *   <li>必须携带 {@code Authorization: Bearer <Widget JWT>}；</li>
 *   <li>调用 {@link JwtUtil#parseWidgetCustomerToken} 校验签名 / 过期 / issuer / audience / role；</li>
 *   <li>校验通过后把 {@link WidgetCustomerPrincipal} 放入 request attribute 与
 *       {@code SecurityContextHolder}（仅授予 {@code ROLE_WIDGET_CUSTOMER} 这一个 authority）；</li>
 *   <li>校验失败返回 401（区分认证失败，Token/请求体不匹配由控制器返回 403）。</li>
 * </ul>
 *
 * <p><b>边界隔离</b>：本过滤器只匹配买家聊天及消息查询路径，其余路径一律放行交给后台过滤链；
 * 它授予的不是 {@code JwtPrincipal}，因此 {@code @PreAuthorize} / {@code TenantAuthorization}
 * 不会把它当成任何后台角色，Widget Token 无法访问后台接口。
 */
@Slf4j
public class WidgetAuthFilter extends OncePerRequestFilter {

    /** 需要 Widget 专用认证的聊天路径。 */
    private static final String WIDGET_CHAT_PATH = "/api/widget/chat/stream";

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WidgetAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 聊天和增量消息查询强制校验买家令牌；会话创建与非买家路径交给其他过滤器
        return !Set.of(WIDGET_CHAT_PATH, "/api/widget/messages").contains(request.getRequestURI());
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // SSE 会在 servlet async dispatch 阶段再次经过过滤器链，需要重建无状态 SecurityContext
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeUnauthorized(response, "缺少或无效的买家会话令牌");
            return;
        }

        WidgetCustomerPrincipal principal;
        try {
            principal = jwtUtil.parseWidgetCustomerToken(authHeader.substring(7));
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            log.warn("Widget 认证失败: path={}, reason={}", request.getRequestURI(), e.getMessage());
            writeUnauthorized(response, "会话已过期或令牌无效，请重新开始咨询");
            return;
        }

        request.setAttribute(Constants.ATTR_WIDGET_PRINCIPAL, principal);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, Set.of(new SimpleGrantedAuthority("ROLE_WIDGET_CUSTOMER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(R.fail("401", message)));
    }
}
