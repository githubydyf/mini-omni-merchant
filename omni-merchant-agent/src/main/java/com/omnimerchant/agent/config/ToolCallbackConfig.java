package com.omnimerchant.agent.config;

import com.omnimerchant.agent.tool.EscalationTools;
import com.omnimerchant.agent.tool.LogisticsTools;
import com.omnimerchant.agent.tool.OrderTools;
import com.omnimerchant.agent.tool.ProductTools;
import com.omnimerchant.knowledge.tool.PolicyTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 @Tool 注解的 bean 注册成 Spring AI 的 ToolCallback。
 *
 * <p>复现自参考项目 {@code config/ToolCallbackConfig}。原项目注册
 * PolicyTools / OrderTools / LogisticsTools / ProductTools / TranslationTools /
 * EscalationTools 六个；当前阶段 TranslationTools（多语言）尚未复现，
 * 因此注册其余五个。
 *
 * <p>PolicyTools 来自 {@code omni-merchant-knowledge}，提供
 * {@code refundPolicyRAG}，供 POLICY_QA Specialist 使用。
 */
@Configuration
public class ToolCallbackConfig {

    @Bean
    public ToolCallbackProvider combinedToolCallbackProvider(
            PolicyTools policyTools,
            OrderTools orderTools,
            LogisticsTools logisticsTools,
            ProductTools productTools,
            EscalationTools escalationTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(policyTools, orderTools, logisticsTools, productTools, escalationTools)
                .build();
    }
}
