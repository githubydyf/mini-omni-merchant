package com.omnimerchant.agent.service;

import com.omnimerchant.agent.dto.ChatStreamEvent;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.entity.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 ReActAgentService.chatEvents 的流式事件语义（不依赖 Spring 容器）：
 * <ul>
 *   <li>事件顺序为 status → delta… → final；</li>
 *   <li>final 只出现一次；</li>
 *   <li>模型异常时只发 error，不发 final，也不落库 assistant 消息；</li>
 *   <li>模型成功时按真实内容落库 assistant 消息。</li>
 * </ul>
 *
 * <p>使用桩 ChatModel 返回真实的分片 Flux，验证的是真实流式链路而非同步拆分。
 */
class ReActAgentServiceStreamTest {

    private static final String CANNED = "七天无理由退货，签收次日起算。";

    /** 桩模型：把固定文本按分片返回，模拟真实流式。 */
    private ChatModel chunkedModel(String... chunks) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return response(CANNED);
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.fromArray(chunks).map(ReActAgentServiceStreamTest::response);
            }
        };
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatModel> provider(ChatModel model) {
        var p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(model);
        return p;
    }

    private ReActAgentService service(ChatModel model, ChatMessagePersistenceService persistence) {
        return service(model, persistence, memory(defaultHistory()));
    }

    private ReActAgentService service(ChatModel model, ChatMessagePersistenceService persistence,
                                      com.omnimerchant.agent.memory.ConversationMemoryService memory) {
        var orchestrator = new AgentOrchestratorService();
        var guard = new AgentExecutionGuardService();
        var toolProvider = mock(ToolCallbackProvider.class);
        // POLICY_QA 只允许 refundPolicyRAG，护栏会校验白名单工具必须存在
        when(toolProvider.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[]{
                stub("refundPolicyRAG")});
        return new ReActAgentService(
                provider(model), toolProvider, orchestrator, guard, null, persistence, memory);
    }

    /** 默认记忆桩：返回「历史 + 当前用户消息」，模拟 ChatController 先落库的语义。 */
    private com.omnimerchant.agent.memory.ConversationMemoryService memory(List<Message> history) {
        var m = mock(com.omnimerchant.agent.memory.ConversationMemoryService.class);
        when(m.getRecentMessages(any(), anyString())).thenReturn(history);
        return m;
    }

    private List<Message> defaultHistory() {
        return List.of(new UserMessage("退货期限是多少天？"));
    }

    /** 历史为：USER / ASSISTANT / USER(当前问题)。 */
    private List<Message> historyWithPriorTurn() {
        return List.of(
                new UserMessage("我的订单 #1001 发货了吗？"),
                new AssistantMessage("订单 #1001 已发货。"),
                new UserMessage("那它什么时候到？"));
    }

    private static org.springframework.ai.tool.ToolCallback stub(String name) {
        var definition = org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name(name)
                .description(name + " 测试桩")
                .inputSchema("{\"type\":\"object\"}")
                .build();
        return new org.springframework.ai.tool.ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }
        };
    }

    private Conversation conversation() {
        var c = new Conversation();
        c.setId(1L);
        c.setConversationUuid("conv-test");
        c.setTenantId(1001L);
        c.setStatus(1);
        return c;
    }

    @Test
    void shouldEmitStatusDeltasThenSingleFinal() {
        var persistence = mock(ChatMessagePersistenceService.class);
        var events = service(chunkedModel("七天", "无理由退货，", "签收次日起算。"), persistence)
                .chatEvents(conversation(), "退货期限是多少天？", "POLICY_QA")
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events.get(0).type()).isEqualTo("status");
        assertThat(events.get(events.size() - 1).type()).isEqualTo("final");
        assertThat(events.stream().filter(e -> "final".equals(e.type())).count()).isEqualTo(1);

        var joinedDelta = events.stream()
                .filter(e -> "translated_delta".equals(e.type()))
                .map(ChatStreamEvent::data)
                .reduce("", String::concat);
        assertThat(joinedDelta).isEqualTo(CANNED);
        assertThat(events.get(events.size() - 1).data()).isEqualTo(CANNED);

        verify(persistence).saveAssistantMessage(any(), anyString(), any(), any());
    }

    @Test
    void modelFailureShouldEmitOnlyErrorWithoutFinalOrPersistence() {
        var persistence = mock(ChatMessagePersistenceService.class);
        var failing = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("boom");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(new IllegalStateException("模型服务不可用"));
            }
        };

        var events = service(failing, persistence)
                .chatEvents(conversation(), "退货期限是多少天？", "POLICY_QA")
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(ChatStreamEvent::type).contains("error");
        assertThat(events).extracting(ChatStreamEvent::type).doesNotContain("final");
        // 失败绝不落库成功回复
        verify(persistence, never()).saveAssistantMessage(any(), anyString(), any(), any());
    }

    @Test
    void emptyModelOutputShouldEmitErrorNotFinal() {
        var persistence = mock(ChatMessagePersistenceService.class);
        var events = service(chunkedModel(), persistence)
                .chatEvents(conversation(), "退货期限是多少天？", "POLICY_QA")
                .collectList()
                .block();

        assertThat(events).extracting(ChatStreamEvent::type).contains("error");
        assertThat(events).extracting(ChatStreamEvent::type).doesNotContain("final");
        verify(persistence, never()).saveAssistantMessage(any(), anyString(), any(), any());
    }

    @Test
    void triageIntentWithoutToolsShouldEmitError() {
        var persistence = mock(ChatMessagePersistenceService.class);
        var events = service(chunkedModel("不会用到"), persistence)
                .chatEvents(conversation(), "随便一句", "UNKNOWN_INTENT")
                .collectList()
                .block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("error");
    }

    @Test
    void shouldSendFullHistoryToModelIncludingCurrentMessageOnce() {
        // 捕获真正传给模型的 Prompt
        var captured = new AtomicReference<Prompt>();
        var model = capturingModel(captured);
        var persistence = mock(ChatMessagePersistenceService.class);

        var events = service(model, persistence, memory(historyWithPriorTurn()))
                .chatEvents(conversation(), "那它什么时候到？", "POLICY_QA")
                .collectList()
                .block();

        assertThat(events).isNotNull();
        // Prompt 里会先插入 defaultSystem 的系统消息，这里只校验对话消息部分
        var instructions = captured.get().getInstructions().stream()
                .filter(m -> m.getMessageType() != org.springframework.ai.chat.messages.MessageType.SYSTEM)
                .toList();
        assertThat(instructions).extracting(m -> m.getMessageType().name())
                .containsExactly("USER", "ASSISTANT", "USER");
        assertThat(instructions).extracting(m -> m.getText())
                .containsExactly("我的订单 #1001 发货了吗？", "订单 #1001 已发货。", "那它什么时候到？");
        // 当前用户消息只出现一次
        assertThat(instructions.stream()
                .filter(m -> "那它什么时候到？".equals(m.getText())).count()).isEqualTo(1);
    }

    @Test
    void assistantReplyShouldBeSyncedToMemoryAfterPersistence() {
        var memoryMock = memory(defaultHistory());
        var persistence = mock(ChatMessagePersistenceService.class);
        var saved = new ChatMessage();
        saved.setRole("assistant");
        saved.setContent(CANNED);
        when(persistence.saveAssistantMessage(any(), anyString(), any(), any())).thenReturn(saved);

        service(chunkedModel("七天", "无理由退货。"), persistence, memoryMock)
                .chatEvents(conversation(), "退货期限是多少天？", "POLICY_QA")
                .collectList()
                .block();

        verify(memoryMock).syncAssistantMessage(any(), any());
    }

    /** 桩模型：记录收到的 Prompt 并返回固定分片。 */
    private ChatModel capturingModel(AtomicReference<Prompt> captured) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                captured.set(prompt);
                return response(CANNED);
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                captured.set(prompt);
                return Flux.just(response("七天无理由退货。"));
            }
        };
    }
}
