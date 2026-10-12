package com.omnimerchant.agent.ratelimit;

import com.omnimerchant.agent.advisor.TokenUsageAdvisor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimitedChatModel 单元测试（任务 §42）。
 */
class RateLimitedChatModelTest {

    private final TokenRateLimiter rateLimiter = mock(TokenRateLimiter.class);
    private final TokenUsageAdvisor usageAdvisor = mock(TokenUsageAdvisor.class);
    private final ChatModel delegate = mock(ChatModel.class);

    @BeforeEach
    void setUp() {
        when(rateLimiter.allowRequest(any(), anyInt(), anyString()))
                .thenReturn(RateLimitResult.ok());
    }

    private RateLimitedChatModel model() {
        return new RateLimitedChatModel(delegate, rateLimiter, usageAdvisor, "deepseek-flash", 1001L);
    }

    private Prompt prompt() {
        var options = DefaultToolCallingChatOptions.builder()
                .toolContext(Map.of("tenantId", 1001L))
                .build();
        return new Prompt(List.of(new AssistantMessage("hi")), options);
    }

    private ChatResponse responseWithUsage(int promptTokens, int completionTokens) {
        var metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))), metadata);
    }

    @Test
    void syncCallShouldPassThroughRateLimiter() {
        when(delegate.call(any(Prompt.class))).thenReturn(responseWithUsage(10, 20));
        model().call(prompt());
        verify(rateLimiter).allowRequest(eq(1001L), anyInt(), anyString());
        verify(rateLimiter).releaseConcurrent(eq(1001L), anyString());
    }

    @Test
    void streamCallShouldAcquireOnSubscribeAndReleaseAfter() {
        when(delegate.stream(any(Prompt.class)))
                .thenReturn(Flux.just(responseWithUsage(5, 6)));

        var flux = model().stream(prompt());
        // 尚未订阅：不应占用额度（Flux 延迟执行）
        verify(rateLimiter, never()).allowRequest(any(), anyInt(), anyString());

        flux.blockLast();

        verify(rateLimiter).allowRequest(eq(1001L), anyInt(), anyString());
        verify(rateLimiter).releaseConcurrent(eq(1001L), anyString());
    }

    @Test
    void rejectedCallShouldThrowAndNotInvokeDelegate() {
        when(rateLimiter.allowRequest(any(), anyInt(), anyString()))
                .thenReturn(RateLimitResult.reject("QPS_LIMITED"));

        assertThatThrownBy(() -> model().call(prompt()))
                .isInstanceOf(RateLimitedChatModel.RateLimitedException.class)
                .hasMessageContaining("QPS_LIMITED");
        verify(delegate, never()).call(any(Prompt.class));
    }

    @Test
    void rejectedStreamShouldEmitErrorNotFakeAnswer() {
        when(rateLimiter.allowRequest(any(), anyInt(), anyString()))
                .thenReturn(RateLimitResult.reject("CONCURRENT_LIMITED"));

        assertThatThrownBy(() -> model().stream(prompt()).blockLast())
                .isInstanceOf(RateLimitedChatModel.RateLimitedException.class);
        verify(delegate, never()).stream(any(Prompt.class));
    }

    @Test
    void streamErrorShouldStillReleaseConcurrent() {
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("模型故障")));

        assertThatThrownBy(() -> model().stream(prompt()).blockLast())
                .isInstanceOf(RuntimeException.class);

        verify(rateLimiter).releaseConcurrent(eq(1001L), anyString());
    }

    @Test
    void streamCancelShouldStillReleaseConcurrent() {
        when(delegate.stream(any(Prompt.class)))
                .thenReturn(Flux.concat(Flux.just(responseWithUsage(1, 1)), Flux.never()));

        var disposable = model().stream(prompt()).subscribe();
        disposable.dispose();

        verify(rateLimiter).releaseConcurrent(eq(1001L), anyString());
    }

    @Test
    void realUsageShouldBeRecorded() {
        when(delegate.call(any(Prompt.class))).thenReturn(responseWithUsage(11, 22));
        model().call(prompt());
        verify(usageAdvisor).record(eq(11), eq(22), eq("deepseek-flash"), anyLong());
        // 真实用量结算进预算
        verify(rateLimiter).recordTokenUsage(eq(1001L), eq(33L));
    }

    @Test
    void missingUsageShouldNotFabricateTokens() {
        var noUsage = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        when(delegate.call(any(Prompt.class))).thenReturn(noUsage);

        model().call(prompt());

        // 未返回真实 Usage 时不得填入固定估算值（1000），也不得结算预算
        verify(usageAdvisor).record(eq(0), eq(0), eq("deepseek-flash"), anyLong());
        verify(rateLimiter, never()).recordTokenUsage(any(), anyLong());
    }

    @Test
    void tenantFromToolContextShouldBeUsedWhenThreadLocalMissing() {
        // 不设置 TenantContextHolder，仅靠 Prompt 的 ToolContext
        when(delegate.call(any(Prompt.class))).thenReturn(responseWithUsage(1, 1));
        model().call(prompt());
        verify(rateLimiter).allowRequest(eq(1001L), anyInt(), anyString());
    }
}
