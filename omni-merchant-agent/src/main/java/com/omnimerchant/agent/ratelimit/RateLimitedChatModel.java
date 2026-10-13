package com.omnimerchant.agent.ratelimit;

import com.omnimerchant.agent.advisor.TokenUsageAdvisor;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * 带三层限流的 ChatModel 装饰器。
 *
 * <p>复现自参考项目 {@code ratelimit/RateLimitedChatModel}，但有一处<b>关键设计差异</b>
 * （任务 §21）：
 *
 * <blockquote>
 * 参考项目在被限流时构造一个普通 {@code ChatResponse}（把 "[RATE_LIMITED]" 包装成
 * AssistantMessage）。本实现<b>不沿用</b>该行为，而是抛出
 * {@link RateLimitedException}。
 * </blockquote>
 *
 * <p>原因：当前 {@code ReActAgentService} 会把模型正常内容写入 {@code chat_message} 与
 * {@code agent_run}。若把限流提示包装成正常回答，会导致「AgentRun=SUCCESS + 保存假的
 * 正常 AssistantMessage」。因此必须走错误通道，最终输出 {@code SSE error}。
 *
 * <p>流式调用使用 {@code Flux.defer}，在<b>真正订阅时</b>才做准入检查，
 * 并在订阅结束（完成/异常/取消）时释放并发租约，避免"方法被调用但从未订阅"造成资源泄漏。
 */
@Slf4j
public class RateLimitedChatModel implements ChatModel {

    /** 准入估算值：只参与预算检查，不代表真实用量，也不会写入累计用量。 */
    public static final int DEFAULT_ESTIMATED_TOKENS = 1000;

    private final ChatModel delegate;
    private final TokenRateLimiter rateLimiter;
    private final TokenUsageAdvisor tokenUsageAdvisor;
    private final String modelName;
    private final Long tenantIdOverride;

    public RateLimitedChatModel(ChatModel delegate, TokenRateLimiter rateLimiter,
                                TokenUsageAdvisor tokenUsageAdvisor, String modelName) {
        this(delegate, rateLimiter, tokenUsageAdvisor, modelName, null);
    }

    /**
     * @param tenantIdOverride 服务端已验证的租户；非空时优先使用它，
     *                         避免依赖 Reactor 线程上的 ThreadLocal。
     */
    public RateLimitedChatModel(ChatModel delegate, TokenRateLimiter rateLimiter,
                                TokenUsageAdvisor tokenUsageAdvisor, String modelName,
                                Long tenantIdOverride) {
        this.delegate = delegate;
        this.rateLimiter = rateLimiter;
        this.tokenUsageAdvisor = tokenUsageAdvisor;
        this.modelName = modelName;
        this.tenantIdOverride = tenantIdOverride;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        var tenantId = resolveTenantId(prompt);
        var leaseToken = UUID.randomUUID().toString();
        var result = rateLimiter.allowRequest(tenantId, DEFAULT_ESTIMATED_TOKENS, leaseToken);
        if (!result.allowed()) {
            throw new RateLimitedException(result.rejectReason());
        }
        var start = System.currentTimeMillis();
        try {
            var response = delegate.call(prompt);
            recordUsage(response, System.currentTimeMillis() - start, tenantId);
            return response;
        } finally {
            rateLimiter.releaseConcurrent(tenantId, leaseToken);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            // 在真正订阅时才准入：Flux 是延迟执行的，方法调用不代表已开始请求
            var tenantId = resolveTenantId(prompt);
            var leaseToken = UUID.randomUUID().toString();
            var result = rateLimiter.allowRequest(tenantId, DEFAULT_ESTIMATED_TOKENS, leaseToken);
            if (!result.allowed()) {
                return Flux.error(new RateLimitedException(result.rejectReason()));
            }
            var start = System.currentTimeMillis();
            var lastResponse = new ChatResponse[1];
            return delegate.stream(prompt)
                    .doOnNext(response -> lastResponse[0] = response)
                    .doFinally(signalType -> {
                        // 覆盖 SUCCESS / ERROR / CANCEL / TIMEOUT
                        rateLimiter.releaseConcurrent(tenantId, leaseToken);
                        if (lastResponse[0] != null) {
                            recordUsage(lastResponse[0], System.currentTimeMillis() - start, tenantId);
                        } else {
                            // 没有拿到任何响应：不记录伪造 Token
                            log.debug("模型未返回响应，不记录 Token 用量：model={}", modelName);
                        }
                    });
        });
    }

    /**
     * 关键：把模型选项委托给被装饰的真实模型。
     *
     * <p>ChatClient 构建 Prompt 时依赖 {@code getOptions().mutate()} 是否为
     * {@code ToolCallingChatOptions.Builder}；若不委托，默认实现返回普通 ChatOptions，
     * 会导致 {@code toolContext} <b>被静默丢弃</b>，模型层与工具层都拿不到 tenantId。
     *
     * <p>注意：不要覆写 {@code getDefaultOptions()} —— 它在 Spring AI 2.0.0 中已
     * {@code @Deprecated(forRemoval = true)}，接口默认实现会自动回落到本方法。
     */
    @Override
    public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return delegate.getOptions();
    }

    /**
     * 解析租户：优先使用构造函数传入的已验证租户；其次读取 Prompt 中服务端构建的
     * ToolContext（{@code tenantId}）—— 因为流式模型运行在 Reactor 线程上，
     * ThreadLocal 不传播，不能只依赖 {@code TenantContextHolder}。
     */
    private Long resolveTenantId(Prompt prompt) {
        if (tenantIdOverride != null) {
            return tenantIdOverride;
        }
        if (prompt != null && prompt.getOptions() instanceof ToolCallingChatOptions toolOptions) {
            var context = toolOptions.getToolContext();
            if (context != null && context.get("tenantId") != null) {
                var value = context.get("tenantId");
                if (value instanceof Number number) {
                    return number.longValue();
                }
                try {
                    return Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    // 落到 ThreadLocal 兜底
                }
            }
        }
        return TenantContextHolder.get();
    }

    /** 从真实响应 metadata 提取 Usage；不存在时保持 null，绝不填估算值。 */
    private void recordUsage(ChatResponse response, long latencyMs, Long tenantId) {
        Integer promptTokens = null;
        Integer completionTokens = null;
        var metadata = response == null ? null : response.getMetadata();
        if (metadata != null) {
            Usage usage = metadata.getUsage();
            if (usage != null) {
                promptTokens = usage.getPromptTokens();
                completionTokens = usage.getCompletionTokens();
            }
        }
        tokenUsageAdvisor.record(promptTokens, completionTokens, modelName, latencyMs);
        // 只在真实采集到正数用量时结算预算；0 / null 表示"未采集"，不得当作真实消耗写入
        var total = (promptTokens == null ? 0 : promptTokens)
                + (completionTokens == null ? 0 : completionTokens);
        if (total > 0) {
            rateLimiter.recordTokenUsage(tenantId, total);
        }
    }

    /** 最近一次调用中提取到的真实 Usage（供上层读取）。 */
    public String modelName() {
        return modelName;
    }

    /** 限流拒绝异常：属于业务拒绝，<b>不得</b>计入模型服务熔断。 */
    public static class RateLimitedException extends RuntimeException {
        private final String rejectReason;

        public RateLimitedException(String rejectReason) {
            super("模型调用被限流拒绝：" + rejectReason);
            this.rejectReason = rejectReason;
        }

        public String rejectReason() {
            return rejectReason;
        }
    }
}
