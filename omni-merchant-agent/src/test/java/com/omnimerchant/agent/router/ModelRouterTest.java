package com.omnimerchant.agent.router;

import com.omnimerchant.agent.advisor.TokenUsageAdvisor;
import com.omnimerchant.agent.ratelimit.TokenRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ModelRouter 单元测试（任务 §43）。
 */
class ModelRouterTest {

    private final TokenRateLimiter rateLimiter = mock(TokenRateLimiter.class);
    private final TokenUsageAdvisor usageAdvisor = mock(TokenUsageAdvisor.class);

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatModel> provider(ChatModel model) {
        var p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(model);
        return p;
    }

    private ModelRouter router(ChatModel model, String modelName) {
        return new ModelRouter(provider(model), rateLimiter, usageAdvisor, modelName);
    }

    @Test
    void configuredDeepSeekShouldRoute() {
        var model = mock(ChatModel.class);
        var routed = router(model, "deepseek-flash").route("POLICY_QA");
        assertThat(routed.available()).isTrue();
        assertThat(routed.providerName()).isEqualTo("deepseek");
    }

    @Test
    void unconfiguredModelShouldBeUnavailable() {
        // getIfAvailable() 返回 null = 未配置 api-key
        var routed = router(null, "deepseek-flash").route("ORDER_STATUS");
        assertThat(routed.available()).isFalse();
        assertThat(routed.chatModel()).isNull();
    }

    @Test
    void realModelNameShouldComeFromConfiguration() {
        var model = mock(ChatModel.class);
        var routed = router(model, "deepseek-flash").route("LOGISTICS");
        // 不得硬编码 deepseek-chat
        assertThat(routed.modelName()).isEqualTo("deepseek-flash");
    }

    @Test
    void allImplementedIntentsShouldRouteToDeepSeek() {
        var model = mock(ChatModel.class);
        var router = router(model, "deepseek-flash");
        for (var intent : new String[]{"ORDER_STATUS", "LOGISTICS", "RETURN_REFUND",
                "PRODUCT_ADVICE", "POLICY_QA", "HUMAN_REQUEST"}) {
            var routed = router.route(intent);
            assertThat(routed.available()).isTrue();
            assertThat(routed.providerName()).isEqualTo("deepseek");
        }
    }

    @Test
    void fallbackShouldNotFabricateOtherProvider() {
        var model = mock(ChatModel.class);
        var router = router(model, "deepseek-flash");
        var fallback = router.fallback();
        // 当前只有一个已配置 provider，fallback 仍是 deepseek，不虚构 openai/anthropic
        assertThat(fallback.providerName()).isEqualTo("deepseek");
    }

    @Test
    void fallbackWhenNothingConfiguredShouldBeUnavailable() {
        var router = router(null, "deepseek-flash");
        assertThat(router.fallback().available()).isFalse();
        assertThat(router.route("POLICY_QA").available()).isFalse();
    }
}
