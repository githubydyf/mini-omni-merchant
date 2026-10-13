package com.omnimerchant.agent.controller;

import com.omnimerchant.agent.dto.ChatStreamEvent;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.service.ReActAgentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 通过真实 MVC 异步完成回调验证上游取消；模型事件为桩，不是模型端到端验证。 */
class ChatSseCancellationTest {
    @Test
    void servletCompletionCancelsModelSubscription() throws Exception {
        var agent = mock(ReActAgentService.class);
        var conversation = new Conversation(); conversation.setConversationUuid("流连接取消测试");
        var subscribed = new CountDownLatch(1); var cancelled = new CountDownLatch(1);
        var signal = new AtomicReference<SignalType>();
        when(agent.chatEvents(conversation, "测试消息", "GENERAL")).thenReturn(
                Flux.concat(Flux.just(ChatStreamEvent.status("正在处理")), Flux.<ChatStreamEvent>never())
                        .doOnSubscribe(s -> subscribed.countDown())
                        .doFinally(s -> { signal.set(s); cancelled.countDown(); }));
        var mvc = MockMvcBuilders.standaloneSetup(new StreamController(agent, conversation)).build();
        var result = mvc.perform(get("/测试流取消")).andReturn();
        assertThat(result.getRequest().isAsyncStarted()).isTrue();
        assertThat(subscribed.await(3, TimeUnit.SECONDS)).isTrue();
        result.getRequest().getAsyncContext().complete();
        assertThat(cancelled.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(signal.get()).isEqualTo(SignalType.CANCEL);
    }

    @RestController
    static class StreamController {
        private final ReActAgentService agent;
        private final Conversation conversation;
        StreamController(ReActAgentService agent, Conversation conversation) { this.agent = agent; this.conversation = conversation; }
        @GetMapping("/测试流取消")
        SseEmitter stream() { return ChatSseSupport.stream(agent, conversation, "测试消息", "GENERAL", "流取消测试"); }
    }
}
