package com.omnimerchant.agent.resilience;

import com.omnimerchant.agent.ratelimit.RateLimitedChatModel;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * LLM 调用的熔断 / 超时 / 安全重试保护。
 *
 * <p>算子顺序（任务 §35）：<pre>
 * 真实模型调用
 *   ↓
 * 熔断保护（不计入业务拒绝）
 *   ↓
 * 超时（每次响应间隔超时）
 *   ↓
 * 安全重试（仅"尚未输出内容 + 尚未执行工具 + 可重试异常"）
 * </pre>
 *
 * <p><b>为什么不能对整轮 Agent 重试</b>（任务 §30）：{@code chatEvents} 内含会话锁、
 * AgentRun、Tool Calling 与副作用登记；重新订阅会重建 AgentRun、重跑工具、
 * 重复创建退款/升级记录。因此重试仅包裹<b>模型调用</b>这一段，且必须满足安全前提。
 */
@Slf4j
@Service
public class LlmResilienceService {

    private final CircuitBreaker llmCircuitBreaker;

    @Value("${app.llm.resilience.timeout-seconds:70}")
    private long timeoutSeconds;

    @Value("${app.llm.resilience.max-retries:1}")
    private long maxRetries;

    @Value("${app.llm.resilience.retry-backoff-ms:300}")
    private long retryBackoffMs;

    public LlmResilienceService(CircuitBreaker llmCircuitBreaker) {
        this.llmCircuitBreaker = llmCircuitBreaker;
    }

    /**
     * 包装一次流式模型调用。
     *
     * @param supplier           真实的模型流（延迟执行）
     * @param safetyGuard        返回 true 表示"此刻仍然安全重试"（未输出内容、未执行工具）
     * @param modelDescription   模型描述（日志用）
     */
    public Flux<ChatResponse> protectStream(Supplier<Flux<ChatResponse>> supplier,
                                            Supplier<Boolean> safetyGuard,
                                            String modelDescription) {
        return Flux.defer(() -> {
            var emittedContent = new AtomicBoolean(false);
            return Flux.defer(supplier)
                    .transformDeferred(CircuitBreakerOperator.of(llmCircuitBreaker))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .doOnNext(response -> {
                        if (hasText(response)) {
                            emittedContent.set(true);
                        }
                    })
                    .retryWhen(Retry.backoff(Math.max(0, maxRetries), Duration.ofMillis(retryBackoffMs))
                            .filter(error -> isSafeToRetry(error, emittedContent.get(), safetyGuard))
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .doOnError(error -> log.warn("LLM 流式调用失败：model={}, 已输出内容={}, error={}",
                            modelDescription, emittedContent.get(), error.getMessage()));
        });
    }

    /**
     * 判断是否允许自动重试（任务 §31/§32）。
     *
     * <p>必须同时满足：① 尚未输出任何有效内容；② 尚未执行任何工具（由调用方提供标记）；
     * ③ 异常属于明确可重试的模型传输故障。
     */
    private boolean isSafeToRetry(Throwable error, boolean emittedContent, Supplier<Boolean> safetyGuard) {
        if (emittedContent) {
            // 已经输出过内容：前端已展示，不得重新生成第二遍
            return false;
        }
        if (safetyGuard != null && !Boolean.TRUE.equals(safetyGuard.get())) {
            // 已经执行过工具（或无法证明未执行）：默认不重试
            return false;
        }
        return isRetryableError(error);
    }

    /**
     * 可重试异常判定（任务 §28/§32）。
     *
     * <p>不计入/不重试的情况：
     * <ul>
     *   <li>限流拒绝（{@link RateLimitedChatModel.RateLimitedException}）；</li>
     *   <li>熔断 OPEN（{@link CallNotPermittedException}）；</li>
     *   <li>参数非法等客户端错误（{@link IllegalArgumentException}）。</li>
     * </ul>
     */
    public boolean isRetryableError(Throwable error) {
        if (error == null) {
            return false;
        }
        if (error instanceof RateLimitedChatModel.RateLimitedException
                || error instanceof CallNotPermittedException
                || error instanceof IllegalArgumentException
                || error instanceof SecurityException) {
            return false;
        }
        // 其余按模型传输故障处理（连接失败 / 5xx / 超时等）
        return true;
    }

    /**
     * 熔断器应当计入的异常判定（任务 §28）。
     *
     * <p>业务拒绝（限流、幂等拦截、白名单拒绝、租户校验失败）不计入，
     * 否则正常业务拒绝会导致模型被错误熔断。
     */
    public boolean isCountedByCircuitBreaker(Throwable error) {
        if (error == null) {
            return false;
        }
        return !(error instanceof RateLimitedChatModel.RateLimitedException
                || error instanceof IllegalArgumentException
                || error instanceof SecurityException);
    }

    public CircuitBreaker.State circuitState() {
        return llmCircuitBreaker.getState();
    }

    public String circuitStateName() {
        return llmCircuitBreaker.getState().name();
    }

    private boolean hasText(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return false;
        }
        var text = response.getResult().getOutput().getText();
        return text != null && !text.isEmpty();
    }

    /** 超时异常类型（供上层分类）。 */
    public boolean isTimeout(Throwable error) {
        return error instanceof TimeoutException;
    }
}
