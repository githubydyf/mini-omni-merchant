package com.omnimerchant.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.dto.WidgetDtos;
import com.omnimerchant.agent.service.ChatMessagePersistenceService;
import com.omnimerchant.agent.service.ConversationLifecycleService;
import com.omnimerchant.agent.service.IntentClassificationService;
import com.omnimerchant.agent.memory.ConversationMemoryService;
import com.omnimerchant.agent.service.ReActAgentService;
import com.omnimerchant.agent.service.WidgetSessionService;
import com.omnimerchant.agent.service.AgentTraceService;
import com.omnimerchant.common.constant.Constants;
import com.omnimerchant.common.dto.R;
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

    private final WidgetSessionService widgetSessionService;
    private final ConversationLifecycleService conversationLifecycleService;
    private final ChatMessagePersistenceService chatMessagePersistenceService;
    private final ConversationMemoryService conversationMemoryService;
    private final IntentClassificationService intentClassificationService;
    private final AgentTraceService agentTraceService;
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
            // 3. 分类前先持久化用户消息；兼容旧请求字段，但不信任买家传入的 intent。
            var savedUserMessage = chatMessagePersistenceService.saveUserMessage(
                    conversation, request.message(), null);
            conversationMemoryService.syncUserMessage(conversation, savedUserMessage);
            var classification = intentClassificationService.classify(conversation, request.message());
            if (classification.failed()) {
                // 保留已保存的用户消息，但不更新会话意图，也不启动 Agent、工具或助手消息落库。
                var traceId = agentTraceService.startChatRun(conversation.getTenantId(),
                        conversation.getConversationUuid(), null, classification.modelProvider(),
                        classification.modelName(), request.message());
                agentTraceService.recordIntentClassification(traceId, request.message(), classification);
                agentTraceService.failRunWithReason(traceId, classification.failureCategory(),
                        classification.failureReason(), classification.latencyMs());
                log.warn("Widget 分类调用失败：tenantId={}, conversationUuid={}, traceId={}, "
                                + "classificationStatus={}, failureCategory={}, modelProvider={}, modelName={}, elapsedMs={}",
                        conversation.getTenantId(), conversation.getConversationUuid(), traceId,
                        classification.status(), classification.failureCategory(), classification.modelProvider(),
                        classification.modelName(), classification.latencyMs());
                return ChatSseSupport.error(classification.failureReason(), conversation.getConversationUuid(), "Widget");
            }
            chatMessagePersistenceService.updateIntent(conversation, classification.intent());

            log.info("Widget 对话开始：tenantId={}, conversationUuid={}",
                    principal.tenantId(), request.conversationUuid());

            // 4. 复用同一 Agent 运行时与 SSE 适配层
            return ChatSseSupport.stream(reActAgentService, conversation, request.message(),
                    classification.intent(), "Widget", classification);
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.set(previous);
            }
        }
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
