package com.omnimerchant.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.dto.WidgetDtos;
import com.omnimerchant.agent.service.ChatMessagePersistenceService;
import com.omnimerchant.agent.service.ConversationLifecycleService;
import com.omnimerchant.agent.service.ReActAgentService;
import com.omnimerchant.agent.service.WidgetSessionService;
import com.omnimerchant.common.constant.Constants;
import com.omnimerchant.common.dto.R;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import com.omnimerchant.common.util.JwtUtil.WidgetCustomerPrincipal;
import com.omnimerchant.tenant.context.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

/**
 * Widget 买家在线客服接口。
 *
 * <ul>
 *   <li>{@code POST /api/widget/session} —— 公开；用真实店铺编码创建会话并签发买家令牌。</li>
 *   <li>{@code POST /api/widget/chat/stream} —— 必须携带 Widget 买家令牌（由
 *       {@code WidgetAuthFilter} 校验），令牌的 tenantCode / conversationUuid 必须与请求体一致。</li>
 * </ul>
 *
 * <p><b>安全边界</b>：买家令牌不是后台 {@code JwtPrincipal}，无任何后台角色 / 权限，
 * 不能访问 {@code /api/orders} 等后台接口。会话与消息全部复用后台同一套
 * Conversation / ChatMessage / Agent Runtime，因此天然进入 Inbox / Trace。
 */
@Slf4j
@RestController
@RequestMapping("/api/widget")
@RequiredArgsConstructor
public class WidgetController {

    /**
     * Widget 允许的咨询意图白名单。
     *
     * <p>只包含只读类咨询；退款 / 退货 / 改址 / 取消等副作用意图仍走各自既有 intent
     * （需真实身份核验 + 工具守卫 + 幂等 + 人工审批）。未知意图回退为 {@code GENERAL}。
     */
    private static final Set<String> WIDGET_ALLOWED_INTENTS = Set.of(
            "GENERAL", "POLICY_QA", "ORDER_STATUS", "LOGISTICS", "PRODUCT_ADVICE", "HUMAN_REQUEST");

    private final WidgetSessionService widgetSessionService;
    private final ConversationLifecycleService conversationLifecycleService;
    private final ChatMessagePersistenceService chatMessagePersistenceService;
    private final ReActAgentService reActAgentService;
    private final ObjectMapper objectMapper;

    /** 创建买家会话（公开）。 */
    @PostMapping("/session")
    public R<WidgetDtos.WidgetSessionResponse> session(@RequestBody WidgetDtos.WidgetSessionRequest request,
                                                       HttpServletRequest httpRequest) {
        var response = widgetSessionService.createSession(request, clientIp(httpRequest));
        // 不记录买家令牌 / 邮箱明文
        log.info("Widget 会话创建成功：tenantId={}, conversationUuid={}",
                response.tenantId(), response.conversationUuid());
        return R.ok(response);
    }

    /** 买家流式对话（需要 Widget 令牌）。 */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody WidgetDtos.WidgetChatRequest request,
                                 HttpServletRequest httpRequest,
                                 HttpServletResponse response) throws IOException {
        var principal = (WidgetCustomerPrincipal) httpRequest.getAttribute(Constants.ATTR_WIDGET_PRINCIPAL);
        // 防御性检查：正常情况下 WidgetAuthFilter 已保证 principal 非空
        if (principal == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                    "缺少或无效的买家会话令牌");
            return null;
        }

        // 1. 令牌与请求体必须匹配（tenantCode / conversationUuid）
        if (request == null
                || !Objects.equals(principal.tenantCode(), request.tenantCode())
                || !Objects.equals(principal.conversationUuid(), request.conversationUuid())) {
            log.warn("Widget 令牌与会话不匹配：tokenTenant={}, requestTenant={}, tokenConv={}, requestConv={}",
                    principal.tenantCode(), request == null ? null : request.tenantCode(),
                    principal.conversationUuid(), request == null ? null : request.conversationUuid());
            writeError(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN,
                    "会话令牌与当前会话不匹配，请重新开始咨询");
            return null;
        }
        if (request.message() == null || request.message().isBlank()) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, ErrorCode.BAD_REQUEST, "消息不能为空");
            return null;
        }
        if (request.message().length() > 2000) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, ErrorCode.BAD_REQUEST,
                    "消息长度不能超过 2000 字符");
            return null;
        }

        // 2. 只信任令牌中的 tenantId 建立租户上下文；会话必须属于该租户
        var previous = TenantContextHolder.get();
        TenantContextHolder.set(principal.tenantId());
        try {
            var conversation = conversationLifecycleService.requireForAi(
                    request.conversationUuid(), principal.tenantId());
            var intent = resolveIntent(request.intent());

            // 3. 先持久化 user 消息（复用后台同一持久化逻辑）
            var savedUserMessage = chatMessagePersistenceService.saveUserMessage(
                    conversation, request.message(), intent);

            log.info("Widget 对话开始：tenantId={}, conversationUuid={}",
                    principal.tenantId(), request.conversationUuid());

            // 4. 复用同一 Agent 运行时与 SSE 适配层
            return ChatSseSupport.stream(reActAgentService, conversation, request.message(), intent, "Widget");
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.set(previous);
            }
        }
    }

    /** 未知意图回退为 GENERAL（安全只读专员），不因客户端乱填意图而放开副作用工具。 */
    private String resolveIntent(String intent) {
        if (intent == null || intent.isBlank()) {
            return "GENERAL";
        }
        var normalized = intent.trim().toUpperCase();
        return WIDGET_ALLOWED_INTENTS.contains(normalized) ? normalized : "GENERAL";
    }

    private String clientIp(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeError(HttpServletResponse response, int status, ErrorCode errorCode, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(errorCode, message)));
        response.getWriter().flush();
    }
}
