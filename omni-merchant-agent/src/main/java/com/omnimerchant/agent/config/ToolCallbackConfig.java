package com.omnimerchant.agent.config;

import com.omnimerchant.agent.tool.EscalationTools;
import com.omnimerchant.agent.tool.LogisticsTools;
import com.omnimerchant.agent.tool.OrderTools;
import com.omnimerchant.agent.tool.ProductTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 @Tool 注解的 bean 注册成 Spring AI 的 ToolCallback。
 *
 * <p>复现自参考项目 {@code config/ToolCallbackConfig}。
 * 原项目注册 PolicyTools / OrderTools / LogisticsTools / ProductTools /
 * TranslationTools / EscalationTools 六个；当前阶段 PolicyTools（知识库 RAG）
 * 与 TranslationTools（多语言）尚未复现，故只注册已完成的四个。
 * 等对应模块复现后再补回来。
 */
@Configuration
public class ToolCallbackConfig {

    @Bean
    public ToolCallbackProvider combinedToolCallbackProvider(
            OrderTools orderTools,
            LogisticsTools logisticsTools,
            ProductTools productTools,
            EscalationTools escalationTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(orderTools, logisticsTools, productTools, escalationTools)
                .build();
    }
}
