package com.omnimerchant.agent.router;

import com.omnimerchant.agent.advisor.TokenUsageAdvisor;
import com.omnimerchant.agent.ratelimit.RateLimitedChatModel;
import com.omnimerchant.agent.ratelimit.TokenRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 模型路由器。
 *
 * <p>复现自参考项目 {@code router/ModelRouter} 的接口设计：按 intent 选择模型，
 * 每个模型都用 {@link RateLimitedChatModel} 包装（三层限流）。
 *
 * <p><b>当前项目只有 DeepSeek</b>（任务 §7/§9）：
 * <ul>
 *   <li>所有已实现 intent 都路由到 DeepSeek；</li>
 *   <li>未配置的 provider <b>视为不可用</b>，不把 DeepSeek 伪装成 OpenAI / Anthropic，
 *       也不虚构"降级到备用模型"；</li>
 *   <li>{@code modelName} 来自真实配置 {@code app.llm.deepseek.model}，
 *       不在代码里硬编码 {@code deepseek-chat}，避免 AgentRun 记录错误模型名。</li>
 * </ul>
 *
 * <p>保留按 intent 路由的结构，后续接入其他 provider 时只需在此扩展规则。
 */
@Slf4j
@Service
public class ModelRouter {

    private static final String PROVIDER_DEEPSEEK = "deepseek";

    private final RoutedModel deepSeekModel;
    private final String configuredModelName;

    public ModelRouter(
            @Qualifier("deepSeekChatModel") ObjectProvider<ChatModel> deepSeekChatModel,
            TokenRateLimiter rateLimiter,
            TokenUsageAdvisor tokenUsageAdvisor,
            @Value("${app.llm.deepseek.model:deepseek-chat}") String deepSeekModelName) {
        this.configuredModelName = deepSeekModelName;
        this.deepSeekModel = wrap(deepSeekChatModel.getIfAvailable(), rateLimiter,
                tokenUsageAdvisor, PROVIDER_DEEPSEEK, deepSeekModelName);
    }

    private RoutedModel wrap(ChatModel model, TokenRateLimiter rateLimiter,
                             TokenUsageAdvisor tokenUsageAdvisor,
                             String providerName, String modelName) {
        if (model == null) {
            log.warn("模型未启用：provider={}, model={}。请配置对应 API Key 后再使用。",
                    providerName, modelName);
            return new RoutedModel(null, providerName, modelName);
        }
        return new RoutedModel(
                new RateLimitedChatModel(model, rateLimiter, tokenUsageAdvisor, modelName),
                providerName, modelName);
    }

    /** 按 intent 路由。当前全部 intent 都使用 DeepSeek。 */
    public RoutedModel route(String intent) {
        RoutedModel selected = selectModel(intent);
        log.info("ModelRouter：intent={} -> provider={}, model={}, available={}",
                intent, selected.providerName(), selected.modelName(), selected.available());
        return selected;
    }

    /**
     * 备用模型。
     *
     * <p>当前只有 DeepSeek 一个已配置 provider，因此 fallback 仍返回 DeepSeek；
     * 若其不可用则返回不可用结果，<b>不虚构</b>其他模型。
     */
    public RoutedModel fallback() {
        return firstAvailable(deepSeekModel);
    }

    private RoutedModel selectModel(String intent) {
        // 当前只实现 DeepSeek：保留 switch 结构便于后续扩展 per-intent 路由
        if (intent == null) {
            return firstAvailable(deepSeekModel);
        }
        return switch (intent.toUpperCase()) {
            // 所有已实现 intent 均使用 DeepSeek（唯一已配置 provider）
            case "ORDER_STATUS", "LOGISTICS", "RETURN_REFUND", "CANCEL_ORDER",
                 "ADDRESS_CHANGE", "PRODUCT_ADVICE", "POLICY_QA",
                 "COMPLAINT", "HUMAN_REQUEST" -> firstAvailable(deepSeekModel);
            default -> firstAvailable(deepSeekModel);
        };
    }

    private RoutedModel firstAvailable(RoutedModel... candidates) {
        for (var candidate : candidates) {
            if (candidate != null && candidate.available()) {
                return candidate;
            }
        }
        // 没有任何可用模型：明确返回不可用，不伪造
        return new RoutedModel(null, "none", configuredModelName);
    }

    /**
     * 路由结果。
     *
     * @param chatModel    已包装限流的模型；不可用时为 null
     * @param providerName 提供商（deepseek）
     * @param modelName    真实配置的模型名（写入 agent_run.model_name）
     */
    public record RoutedModel(ChatModel chatModel, String providerName, String modelName) {
        public boolean available() {
            return chatModel != null;
        }
    }
}
