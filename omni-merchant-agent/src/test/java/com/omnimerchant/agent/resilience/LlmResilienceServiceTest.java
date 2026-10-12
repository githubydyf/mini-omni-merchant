package com.omnimerchant.agent.resilience;

import com.omnimerchant.agent.ratelimit.RateLimitedChatModel;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 熔断 + 超时 + 安全重试测试（任务 §44 / §45）。
 */
class LlmResilienceServiceTest {

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private LlmResilienceService service(CircuitBreaker breaker, long maxRetries) {
        var s = new LlmResilienceService(breaker);
        ReflectionTestUtils.setField(s, "timeoutSeconds", 5L);
        ReflectionTestUtils.setField(s, "maxRetries", maxRetries);
        ReflectionTestUtils.setField(s, "retryBackoffMs", 10L);
        return s;
    }

    private CircuitBreaker breaker(int slidingWindow, int minimumCalls, float failureRate, long waitSeconds) {
        var config = CircuitBreakerConfig.custom()
                .slidingWindowSize(slidingWindow)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureRate)
                .waitDurationInOpenState(Duration.ofSeconds(waitSeconds))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();
        return CircuitBreakerRegistry.of(config).circuitBreaker("llm-test");
    }

    // ---------- 熔断状态 ----------

    @Test
    void initialStateShouldBeClosed() {
        var s = service(breaker(4, 2, 50f, 1), 0);
        assertThat(s.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void failuresShouldOpenCircuit() {
        var cb = breaker(4, 2, 50f, 60);
        var s = service(cb, 0);

        // 连续失败达到阈值
        for (int i = 0; i < 4; i++) {
            StepVerifier.create(s.protectStream(
                            () -> Flux.error(new RuntimeException("模型服务 5xx")),
                            () -> true, "deepseek-flash"))
                    .expectError()
                    .verify();
        }
        assertThat(s.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void openCircuitShouldRejectWithoutCallingModel() {
        var cb = breaker(2, 2, 50f, 60);
        var s = service(cb, 0);
        var invoked = new AtomicBoolean(false);

        for (int i = 0; i < 2; i++) {
            StepVerifier.create(s.protectStream(() -> Flux.error(new RuntimeException("故障")),
                    () -> true, "deepseek-flash")).expectError().verify();
        }
        assertThat(s.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        StepVerifier.create(s.protectStream(
                        () -> {
                            invoked.set(true);
                            return Flux.just(textResponse("不应被调用"));
                        },
                        () -> true, "deepseek-flash"))
                .expectError(CallNotPermittedException.class)
                .verify();

        // 熔断 OPEN 时不得触发真实模型请求
        assertThat(invoked.get()).isFalse();
    }

    @Test
    void afterWaitProbeCallShouldBePermitted() throws Exception {
        var cb = breaker(2, 2, 50f, 1);
        var s = service(cb, 0);
        for (int i = 0; i < 2; i++) {
            StepVerifier.create(s.protectStream(() -> Flux.error(new RuntimeException("故障")),
                    () -> true, "deepseek-flash")).expectError().verify();
        }
        assertThat(s.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        // resilience4j 2.x 在 OPEN 等待期结束后，于下一次调用尝试时才转为 HALF_OPEN；
        // 因此这里验证"等待后探测请求被允许"（即不再抛出 CallNotPermitted）
        Thread.sleep(1200);
        StepVerifier.create(s.protectStream(() -> Flux.just(textResponse("探测成功")),
                        () -> true, "deepseek-flash"))
                .expectNextCount(1)
                .verifyComplete();
        assertThat(s.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpenSuccessShouldCloseCircuit() throws Exception {
        var cb = breaker(2, 2, 50f, 1);
        var s = service(cb, 0);
        for (int i = 0; i < 2; i++) {
            StepVerifier.create(s.protectStream(() -> Flux.error(new RuntimeException("故障")),
                    () -> true, "deepseek-flash")).expectError().verify();
        }
        Thread.sleep(1200);

        StepVerifier.create(s.protectStream(() -> Flux.just(textResponse("恢复")),
                () -> true, "deepseek-flash")).expectNextCount(1).verifyComplete();

        assertThat(s.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ---------- 异常分类（不计入熔断 / 不重试）----------

    @Test
    void rateLimitRejectionShouldNotCountTowardCircuitBreaker() {
        var s = service(breaker(4, 2, 50f, 60), 0);
        assertThat(s.isCountedByCircuitBreaker(
                new RateLimitedChatModel.RateLimitedException("QPS_LIMITED"))).isFalse();
        assertThat(s.isCountedByCircuitBreaker(new IllegalArgumentException("非法参数"))).isFalse();
        assertThat(s.isCountedByCircuitBreaker(new SecurityException("白名单拒绝"))).isFalse();
        // 真实传输故障才计入
        assertThat(s.isCountedByCircuitBreaker(new RuntimeException("连接失败"))).isTrue();
    }

    @Test
    void rateLimitAndToolAndIllegalShouldNotBeRetried() {
        var s = service(breaker(4, 2, 50f, 60), 1);
        assertThat(s.isRetryableError(
                new RateLimitedChatModel.RateLimitedException("CONCURRENT_LIMITED"))).isFalse();
        assertThat(s.isRetryableError(
                io.github.resilience4j.circuitbreaker.CallNotPermittedException
                        .createCallNotPermittedException(breaker(4, 2, 50f, 60)))).isFalse();
        assertThat(s.isRetryableError(new IllegalArgumentException("非法请求"))).isFalse();
        assertThat(s.isRetryableError(new SecurityException("权限拒绝"))).isFalse();
        assertThat(s.isRetryableError(new RuntimeException("连接重置"))).isTrue();
    }

    // ---------- 安全重试 ----------

    @Test
    void shouldRetryWhenNoContentEmittedAndNoToolExecuted() {
        var s = service(breaker(100, 100, 99f, 60), 1);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        Flux<ChatResponse> flaky = Flux.defer(() -> {
            if (attempts.incrementAndGet() == 1) {
                return Flux.error(new RuntimeException("连接失败"));
            }
            return Flux.just(textResponse("重试成功"));
        });

        StepVerifier.create(s.protectStream(() -> flaky, () -> true, "deepseek-flash"))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void shouldNotRetryAfterContentEmitted() {
        var s = service(breaker(100, 100, 99f, 60), 1);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        Flux<ChatResponse> partialThenFail = Flux.defer(() -> {
            attempts.incrementAndGet();
            return Flux.concat(
                    Flux.just(textResponse("部分内容")),
                    Flux.error(new RuntimeException("连接中断")));
        });

        StepVerifier.create(s.protectStream(() -> partialThenFail, () -> true, "deepseek-flash"))
                .expectNextCount(1)
                .expectError()
                .verify();

        // 已输出内容后不得重新生成第二遍
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void shouldNotRetryAfterToolExecuted() {
        var s = service(breaker(100, 100, 99f, 60), 1);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        Flux<ChatResponse> failing = Flux.defer(() -> {
            attempts.incrementAndGet();
            return Flux.error(new RuntimeException("连接失败"));
        });

        StepVerifier.create(s.protectStream(() -> failing, () -> false, "deepseek-flash"))
                .expectError()
                .verify();

        // 已执行工具（安全守卫返回 false）时不得重试
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void shouldNotRetryRateLimitRejection() {
        var s = service(breaker(100, 100, 99f, 60), 1);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        Flux<ChatResponse> rejected = Flux.defer(() -> {
            attempts.incrementAndGet();
            return Flux.error(new RateLimitedChatModel.RateLimitedException("QPS_LIMITED"));
        });

        StepVerifier.create(s.protectStream(() -> rejected, () -> true, "deepseek-flash"))
                .expectError(RateLimitedChatModel.RateLimitedException.class)
                .verify();

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void timeoutShouldEmitError() {
        var s = service(breaker(100, 100, 99f, 60), 0);
        ReflectionTestUtils.setField(s, "timeoutSeconds", 1L);

        StepVerifier.create(s.protectStream(
                        () -> Flux.concat(Flux.just(textResponse("a")), Flux.never()),
                        () -> true, "deepseek-flash")
                        .timeout(Duration.ofSeconds(3)))
                .expectNextCount(1)
                .expectError()
                .verify(Duration.ofSeconds(4));
    }
}
