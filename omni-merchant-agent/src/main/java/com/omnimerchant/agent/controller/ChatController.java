package com.omnimerchant.agent.controller;

import com.omnimerchant.agent.dto.ChatRequest;
import com.omnimerchant.agent.memory.ConversationMemoryService;
import com.omnimerchant.agent.service.ChatMessagePersistenceService;
import com.omnimerchant.agent.service.ConversationLifecycleService;
import com.omnimerchant.agent.service.ReActAgentService;
import com.omnimerchant.tenant.context.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.scheduler.Schedulers;

/**
 * 正式流式对话接口（SSE）。
 *
 * <p>复现自参考项目 {@code controller/ChatController}：
 * {@code POST /api/chat/stream}，{@code produces=text/event-stream}，
 * 事件名 {@code status / translated_delta / final / error}。
 *
 * <p>本阶段差异：
 * <ul>
 *   <li>不依赖 JWT；开发阶段允许通过 {@code X-Tenant-Id} 传入租户，但<b>仅用于一致性校验</b>，
 *       权威租户以数据库会话行为准（见 {@link ConversationLifecycleService}）。</li>
 *   <li>会话必须真实存在；不存在时返回明确的业务错误（不自动编造会话）。</li>
 *   <li>user 消息在订阅模型流之前落库；assistant 消息由 ReActAgentService 在成功时落库。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ReActAgentService reActAgentService;
    private final ConversationLifecycleService conversationLifecycleService;
    private final ChatMessagePersistenceService chatMessagePersistenceService;
    private final ConversationMemoryService conversationMemoryService;

    public ChatController(ReActAgentService reActAgentService,
                          ConversationLifecycleService conversationLifecycleService,
                          ChatMessagePersistenceService chatMessagePersistenceService,
                          ConversationMemoryService conversationMemoryService) {
        this.reActAgentService = reActAgentService;
        this.conversationLifecycleService = conversationLifecycleService;
        this.chatMessagePersistenceService = chatMessagePersistenceService;
        this.conversationMemoryService = conversationMemoryService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request,
                             @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        // 5 分钟超时，与原项目一致
        var emitter = new SseEmitter(300_000L);

        // 1. 会话与租户/状态校验（失败直接抛业务异常，由全局处理器返回 JSON，未开始 SSE）
        var conversation = conversationLifecycleService.requireForAi(
                request.conversationUuid(), headerTenantId);
        var tenantId = conversation.getTenantId();
        var intent = request.intent() != null && !request.intent().isBlank()
                ? request.intent() : "UNCLEAR";

        // 2. 先持久化 user 消息：模型失败 / 客户端断开也要保留
        var savedUserMessage = chatMessagePersistenceService.saveUserMessage(
                conversation, request.message(), intent);
        // MySQL 保存成功后同步 Redis 短期记忆（尽力而为；失败时下轮从 MySQL 重建）
        conversationMemoryService.syncUserMessage(conversation, savedUserMessage);

        log.info("Chat 流式开始：tenant={}, conv={}, intent={}, msgLen={}",
                tenantId, request.conversationUuid(), intent, request.message().length());




        // 客户端断开时释放资源
        emitter.onCompletion(() -> log.info("Chat 流式结束：conv={}", request.conversationUuid()));
        emitter.onTimeout(() -> {
            log.warn("Chat 流式超时：conv={}", request.conversationUuid());
            emitter.complete();
        });
        emitter.onError(error -> log.warn("Chat 流式连接异常：conv={}, error={}",
                request.conversationUuid(), error.getMessage()));

        // 3. 订阅模型流并转发为 SSE（工具在 boundedElastic 线程执行，已由 ToolCallbackScope 绑定上下文）
        reActAgentService.chatEvents(conversation, request.message(), intent)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name(event.type())
                                        .data(event.data()));
                            } catch (Exception e) {
                                // 客户端断开或写入失败：不再尝试写普通响应，结束流
                                log.warn("SSE 发送失败（客户端可能已断开）：conv={}, error={}",
                                        request.conversationUuid(), e.getMessage());
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            log.error("Chat 流式错误：conv={}, error={}",
                                    request.conversationUuid(), error.getMessage());
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data("本次请求暂时无法处理，请稍后重试或转人工客服。"));
                            } catch (Exception ex) {
                                log.warn("发送 error 事件失败：{}", ex.getMessage());
                            }
                            emitter.complete();
                        },
                        () -> {
                            emitter.complete();
                            log.info("Chat 流式完成：conv={}", request.conversationUuid());
                        }
                );

        return emitter;
    }
}
