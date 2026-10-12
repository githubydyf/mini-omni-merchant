package com.omnimerchant.agent.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import com.omnimerchant.agent.ratelimit.RateLimitedChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LLM 配置：DeepSeek ChatModel（本阶段固定单一模型）+ LLM 熔断器。
 *
 * <p>DeepSeek 提供 OpenAI 兼容接口，因此直接使用 Spring AI 的
 * {@link OpenAiChatModel} + 自定义 baseUrl/apiKey 构建。
 *
 * <p>复现自参考项目 {@code config/LlmConfiguration} 的 DeepSeek 与熔断器部分，
 * 但当前只保留 DeepSeek 单一模型（原项目还包含 OpenAI / Anthropic）。
 *
 * <p>未配置 api-key 时不创建 ChatModel Bean，Agent 接口会返回明确的"模型未配置"提示；
 * ModelRouter 会把该 provider 视为不可用。
 */
@Configuration
public class LlmConfiguration {

    /** 生成温度，与原项目保持一致（0.3）。 */
    private static final Double TEMPERATURE = 0.3;
    /** 输出上限。 */
    private static final Integer MAX_TOKENS = 4096;

    @Bean
    @ConditionalOnExpression("'${app.llm.deepseek.api-key:}' != ''")
    public ChatModel deepSeekChatModel(
            @Value("${app.llm.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${app.llm.deepseek.api-key:}") String apiKey,
            @Value("${app.llm.deepseek.model:deepseek-chat}") String model) {
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .model(model)
                        .temperature(TEMPERATURE)
                        .maxTokens(MAX_TOKENS)
                        .build())
                .build();
    }

    /**
     * 熔断器仓库。
     *
     * <p>配置来自 application.yml，避免把阈值散落在多个 Java 类中（任务 §27）。
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(
            @Value("${app.llm.resilience.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${app.llm.resilience.minimum-number-of-calls:5}") int minimumNumberOfCalls,
            @Value("${app.llm.resilience.sliding-window-size:10}") int slidingWindowSize,
            @Value("${app.llm.resilience.wait-duration-in-open-state-seconds:30}") long waitSeconds) {
        var config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .slidingWindowSize(slidingWindowSize)
                .waitDurationInOpenState(Duration.ofSeconds(waitSeconds))
                .permittedNumberOfCallsInHalfOpenState(3)
                // 业务拒绝不应触发熔断（任务 §28）
                .ignoreExceptions(
                        RateLimitedChatModel.RateLimitedException.class,
                        IllegalArgumentException.class,
                        SecurityException.class)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    /** LLM 调用熔断器（CLOSED / OPEN / HALF_OPEN）。 */
    @Bean
    public CircuitBreaker llmCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("llm");
    }
}
