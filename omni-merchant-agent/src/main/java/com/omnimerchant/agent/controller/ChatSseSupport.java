package com.omnimerchant.agent.controller;

import com.omnimerchant.agent.dto.ChatStreamEvent;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.service.ReActAgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.scheduler.Schedulers;

/**
 * Chat SSE 转发适配层。
 *
 * <p>把 {@link ReActAgentService#chatEvents} 的 {@link ChatStreamEvent} 流转发为
 * {@link SseEmitter}。后台 {@code ChatController} 与买家 {@code WidgetController}
 * 共用本类，避免在入口层复制模型执行 / SSE 细节。
 *
 * <p>事件名保持 {@code status / translated_delta / final / error}，不改名。
 * 客户端断开或写入失败时结束流：订阅随之取消，{@code ReActAgentService} 内部
 * 通过 {@code Flux.using} 释放 Redis 会话锁与限流并发额度。
 */
@Slf4j
public final class ChatSseSupport {

    /** SSE 超时（毫秒），与原项目一致：5 分钟。 */
    public static final long SSE_TIMEOUT_MS = 300_000L;

    private static final String LOG_LABEL = "Chat 流式";

    private ChatSseSupport() {
    }

    /**
     * 订阅 Agent 流并转发为 SSE。
     *
     * @param label 日志标签（例如 "Chat" / "Widget"）
     */
    public static SseEmitter stream(ReActAgentService reActAgentService, Conversation conversation,
                                    String message, String intent, String label) {
        var emitter = new SseEmitter(SSE_TIMEOUT_MS);
        var conversationUuid = conversation.getConversationUuid();

        emitter.onCompletion(() -> log.info("{}结束：conv={}", label, conversationUuid));
        emitter.onTimeout(() -> {
            log.warn("{}超时：conv={}", label, conversationUuid);
            emitter.complete();
        });
        emitter.onError(error -> log.warn("{}连接异常：conv={}, error={}", label, conversationUuid, error.getMessage()));

        reActAgentService.chatEvents(conversation, message, intent)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> {
                            try {
                                emitter.send(SseEmitter.event().name(event.type()).data(event.data()));
                            } catch (Exception e) {
                                // 客户端断开或写入失败：结束流，取消上游订阅
                                log.warn("{}发送失败（客户端可能已断开）：conv={}, error={}",
                                        label, conversationUuid, e.getMessage());
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            log.error("{}错误：conv={}, error={}", label, conversationUuid, error.getMessage());
                            try {
                                emitter.send(SseEmitter.event().name(ChatStreamEvent.TYPE_ERROR)
                                        .data("本次请求暂时无法处理，请稍后重试或转人工客服。"));
                            } catch (Exception ex) {
                                log.warn("{}发送 error 事件失败：{}", label, ex.getMessage());
                            }
                            emitter.complete();
                        },
                        () -> {
                            emitter.complete();
                            log.info("{}完成：conv={}", label, conversationUuid);
                        }
                );
        return emitter;
    }

    /** 默认日志标签。 */
    public static String defaultLabel() {
        return LOG_LABEL;
    }
}
