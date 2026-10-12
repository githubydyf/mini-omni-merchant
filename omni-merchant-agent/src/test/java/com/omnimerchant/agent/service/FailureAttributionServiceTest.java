package com.omnimerchant.agent.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** 失败归因分类测试（只依据真实异常/错误信息）。 */
class FailureAttributionServiceTest {

    private final FailureAttributionService service = new FailureAttributionService();

    @Test
    void shouldClassifyTimeout() {
        assertThat(service.classify(new TimeoutException("read timed out"))).isEqualTo("LLM_TIMEOUT");
    }

    @Test
    void shouldClassifyModelNotConfigured() {
        assertThat(service.classifyMessage("未配置 DeepSeek 模型，请检查配置"))
                .isEqualTo("MODEL_UNAVAILABLE");
    }

    @Test
    void shouldClassifyToolException() {
        assertThat(service.classifyMessage("工具缺失：[refundPolicyRAG]")).isEqualTo("TOOL_EXCEPTION");
        assertThat(service.classify(new IllegalStateException("tool execution failed")))
                .isEqualTo("TOOL_EXCEPTION");
    }

    @Test
    void shouldClassifyTenant() {
        assertThat(service.classifyMessage("租户与会话不一致，拒绝处理")).isEqualTo("TENANT");
    }

    @Test
    void shouldClassifyRateLimitedAndCircuitOpen() {
        assertThat(service.classify(
                new com.omnimerchant.agent.ratelimit.RateLimitedChatModel.RateLimitedException("QPS_LIMITED")))
                .isEqualTo("RATE_LIMITED");
        var cb = io.github.resilience4j.circuitbreaker.CircuitBreaker
                .ofDefaults("t");
        assertThat(service.classify(
                io.github.resilience4j.circuitbreaker.CallNotPermittedException
                        .createCallNotPermittedException(cb)))
                .isEqualTo("CIRCUIT_OPEN");
    }

    @Test
    void shouldFallbackToUnknown() {
        assertThat(service.classify(null)).isEqualTo("UNKNOWN");
        assertThat(service.classifyMessage("")).isEqualTo("UNKNOWN");
        assertThat(service.classifyMessage("某种未归类的问题")).isEqualTo("UNKNOWN");
    }
}
