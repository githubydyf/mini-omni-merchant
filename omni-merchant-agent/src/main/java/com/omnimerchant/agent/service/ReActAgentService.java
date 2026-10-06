package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omnimerchant.agent.context.CallContextHolder;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.mapper.ConversationMapper;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * ReAct Agent 主链（本阶段为最小版）。
 *
 * <p>复现自参考项目 {@code service/ReActAgentService} 的主执行链，但只保留当前
 * 已有真实业务基础的部分：
 *
 * <pre>
 * 用户消息
 *   ↓
 * AgentOrchestratorService.plan(intent, message)   —— 纯规则编排
 *   ↓
 * SpecialistPlan（含 toolAllowlist）
 *   ↓
 * AgentExecutionGuardService.guardedCallbacks()    —— Java 层过滤 Tool
 *   ↓
 * ChatClient（只注册过滤后的 Tool）+ 中文 System Prompt
 *   ↓
 * DeepSeek 决策 → 调用业务 Tool → Tool 结果
 *   ↓
 * DeepSeek 组织最终中文回答
 * </pre>
 *
 * <p>本阶段相对原版去掉的能力（依赖尚未复现的模块）：
 * RedisChatMemory（多轮记忆）、ModelRouter（多模型路由）、MultiLingualEngine（多语言）、
 * SafeGuardAdvisor、TokenUsageAdvisor、CircuitBreaker、AgentTraceService、
 * AgentStateMachineService、TranslationEvidenceService、ObservationRegistry、
 * Redis 会话锁与幂等守卫。
 *
 * <p>当前固定使用 DeepSeek；未配置 API Key 时返回明确的“模型未配置”提示，不抛异常。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReActAgentService {

    /** 中文 System Prompt（面向模型）。业务约束与原项目英文版一致，但改用中文表述。 */
    private static final String SYSTEM_PROMPT = """
            你是一名跨境电商智能客服。

            你需要根据客户问题，使用当前允许使用的工具获取真实业务数据，并基于工具结果回答客户。

            决策过程：
            1. 判断客户真正需要解决的问题。
            2. 如果需要真实业务数据，调用当前允许使用的工具。
            3. 根据工具返回结果继续判断。
            4. 信息充分后给出最终回答。

            必须遵守以下规则：
            - 不得编造订单号、物流单号、订单状态、商品信息或客户信息。
            - 涉及订单、物流、商品等真实业务数据时，应优先使用工具查询，不得凭空回答。
            - 只能使用当前 Specialist 被允许使用的工具。
            - 退货、退款、补发和修改地址相关工具只会创建内部申请，不会直接修改外部电商平台。
            - 如果退款申请只是进入人工审批，不得告诉客户“退款已经成功”或“退款已经到账”。
            - 如果补发申请只是进入人工审批，不得告诉客户“已经补发”。
            - 如果修改地址申请只是进入人工审批，不得告诉客户“地址已经修改成功”。
            - 如果客户明确要求人工客服，并且当前允许使用人工升级工具，应调用人工升级工具。
            - 回答必须以工具真实返回的数据为依据。
            - 不确定时不要编造事实。
            - 默认使用中文回答。
            - 回答应简洁、清楚、符合客服场景。
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ToolCallbackProvider toolCallbackProvider;
    private final AgentOrchestratorService agentOrchestratorService;
    private final AgentExecutionGuardService agentExecutionGuardService;
    private final ConversationMapper conversationMapper;

    /**
     * 单轮对话入口。
     *
     * @param conversationUuid 会话 UUID（用于关联 Tool 审计日志、推导 tenantId）
     * @param userMessage      用户自然语言消息
     * @param intent           已知意图（本阶段显式传入，不做自动意图识别）
     * @return 面向客户的最终中文回答
     */
    public String chat(String conversationUuid, String userMessage, String intent) {
        var plan = agentOrchestratorService.plan(intent, userMessage);
        log.info("Agent 编排：intent={}, specialist={}, allowlist={}",
                intent, plan.specialistKey(), plan.toolAllowlist());

        // 尚未复现的 specialist：政策 RAG。
        if ("policy_rag".equals(plan.specialistKey())) {
            return "政策知识模块暂未启用，暂时无法回答政策类问题。";
        }
        // 默认分流（triage）：本阶段不做自动意图识别，要求调用方显式给出已知意图。
        if (plan.toolAllowlist().isEmpty()) {
            return "当前意图暂不支持自动处理（未识别到可用意图，或该意图尚未接入处理链）。";
        }

        var chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            log.warn("Agent 调用被拒绝：未配置 DeepSeek 模型（DEEPSEEK_API_KEY 缺失）");
            return "[配置错误] 未配置 DeepSeek 模型，请设置 DEEPSEEK_API_KEY 后再调用 Agent。";
        }

        var tenantId = tenantIdOf(conversationUuid);
        TenantContextHolder.set(tenantId);
        CallContextHolder.set(intent, conversationUuid);
        try {
            var callbacks = agentExecutionGuardService.guardedCallbacks(
                    toolCallbackProvider.getToolCallbacks(), plan);
            // Spring AI 2.0.0 起 defaultToolCallbacks(...) 已废弃，统一改用 defaultTools(...)；
            // 它会按元素类型识别 ToolCallback / ToolCallbackProvider / Collection。
            var chatClient = ChatClient.builder(chatModel)
                    .defaultTools(callbacks)
                    .defaultSystem(systemPrompt(plan))
                    .build();
            return chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Agent 执行失败：conv={}, intent={}, error={}", conversationUuid, intent, e.getMessage());
            return "[AGENT_UNAVAILABLE] 本次请求暂时无法处理，请稍后重试或转人工客服。";
        } finally {
            // 防止 ThreadLocal 污染后续请求
            CallContextHolder.clear();
            TenantContextHolder.clear();
        }
    }

    /** 中文 System Prompt + 追加当前 Specialist 的运行时信息（不暴露全量 Tool 名单）。 */
    private String systemPrompt(AgentOrchestratorService.SpecialistPlan plan) {
        return SYSTEM_PROMPT + "\n"
                + "当前处理角色：" + plan.specialistLabel() + "\n"
                + "当前只允许使用系统为本次请求注册的工具。\n"
                + "当前风险等级：" + plan.riskLevel() + "\n"
                + "当前请求是否需要身份验证：" + plan.requiresIdentityVerification() + "\n"
                + "当前请求是否涉及人工审批：" + plan.requiresApproval() + "\n";
    }

    /** 从会话推导 tenantId（供 ToolAuditService 记录 tenant_id）。会话不存在时返回 null。 */
    private Long tenantIdOf(String conversationUuid) {
        if (conversationUuid == null || conversationUuid.isBlank()) {
            return TenantContextHolder.get();
        }
        var conversation = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getConversationUuid, conversationUuid)
                .last("LIMIT 1"));
        return conversation == null ? null : conversation.getTenantId();
    }

    /** 供测试/调试查询编排结果（不触发模型调用）。 */
    public AgentOrchestratorService.SpecialistPlan planFor(String intent, String userMessage) {
        return agentOrchestratorService.plan(intent, userMessage);
    }
}
