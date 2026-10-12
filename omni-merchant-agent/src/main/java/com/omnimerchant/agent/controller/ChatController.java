package com.omnimerchant.agent.controller;

import com.omnimerchant.agent.dto.ChatRequest;
import com.omnimerchant.agent.memory.ConversationMemoryService;
import com.omnimerchant.agent.service.ChatMessagePersistenceService;
import com.omnimerchant.agent.service.ConversationLifecycleService;
import com.omnimerchant.agent.service.ReActAgentService;
import com.omnimerchant.tenant.context.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 正式后台流式对话接口（SSE）。
 *
 * <p>复现自参考项目 {@code controller/ChatController}：
 * {@code POST /api/chat/stream}，{@code produces=text/event-stream}，
 * 事件名 {@code status / translated_delta / final / error}。
 *
 * <p><b>安全边界</b>：
 * <ul>
 *   <li>需要合法后台 JWT（{@code /api/chat} 属受保护路径，无令牌返回 401）；</li>
 *   <li>租户来自已验证请求的 {@link TenantContextHolder}（{@code TenantInterceptor} 校验
 *       {@code X-Tenant-Id} 属于 JWT membership 后设置），<b>不</b>直接信任请求头；</li>
 *   <li>会话的 {@code tenantId} 必须与已验证租户一致，否则在启动 Agent 前即拒绝；</li>
 *   <li>user 消息在订阅模型流之前落库；assistant 消息由 ReActAgentService 在成功时落库。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ReActAgentService reActAgentService;
    private final ConversationLifecycleService conversationLifecycleService;
    private final ChatMessagePersistenceService chatMessagePersistenceService;
    private final ConversationMemoryService conversationMemoryService;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        // 1. 会话与租户/状态校验：租户来自已验证上下文，必须与会话租户一致
        var conversation = conversationLifecycleService.requireForAi(
                request.conversationUuid(), TenantContextHolder.get());
        var intent = request.intent() != null && !request.intent().isBlank()
                ? request.intent() : "UNCLEAR";

        // 2. 先持久化 user 消息：模型失败 / 客户端断开也要保留
        var savedUserMessage = chatMessagePersistenceService.saveUserMessage(
                conversation, request.message(), intent);
        conversationMemoryService.syncUserMessage(conversation, savedUserMessage);

        log.info("Chat 流式开始：tenant={}, conv={}, intent={}, msgLen={}",
                conversation.getTenantId(), request.conversationUuid(), intent, request.message().length());

        // 3. 转发为 SSE（与 Widget 共用适配层）
        return ChatSseSupport.stream(reActAgentService, conversation, request.message(), intent, "Chat");
    }
}
