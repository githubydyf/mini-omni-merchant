package com.omnimerchant.agent.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 配置：DeepSeek ChatModel（本阶段固定单一模型）。
 *
 * <p>DeepSeek 提供 OpenAI 兼容接口，因此直接使用 Spring AI 的
 * {@link OpenAiChatModel} + 自定义 baseUrl/apiKey 构建。
 *
 * <p>复现自参考项目 {@code config/LlmConfiguration} 的 DeepSeek 部分，
 * 但当前只保留 DeepSeek 单一模型（原项目还包含 OpenAI / Anthropic 与 ModelRouter）。
 *
 * <p>未配置 api-key 时不创建该 Bean，Agent 接口会返回明确的“模型未配置”提示。
 */
@Configuration
public class LlmConfiguration {

    /** 生成温度，与原项目保持一致（0.3）。 */
    private static final Double TEMPERATURE = 0.3;
    /** 输出上限。 */
    private static final Integer MAX_TOKENS = 4096;

    @Bean
    @ConditionalOnExpression("'${omnimerchant.llm.deepseek.api-key:}' != ''")
    public ChatModel deepSeekChatModel(
            @Value("${omnimerchant.llm.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${omnimerchant.llm.deepseek.api-key:}") String apiKey,
            @Value("${omnimerchant.llm.deepseek.model:deepseek-chat}") String model) {
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
}
