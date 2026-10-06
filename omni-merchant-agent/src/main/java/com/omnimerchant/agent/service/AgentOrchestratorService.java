package com.omnimerchant.agent.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Agent 编排（Supervisor-Worker 的调度层）。
 *
 * <p>复现自参考项目 {@code service/AgentOrchestratorService}。职责是**纯规则**地把
 * 已知 intent 映射成一个 {@link SpecialistPlan}：由哪个 specialist 处理、
 * 本次允许使用哪些 Tool、风险等级、是否需要身份核验 / 人工审批 / 转人工。
 *
 * <p>本类不调用大模型，也不执行 Tool；真正的 Tool 白名单过滤由
 * {@code AgentExecutionGuardService} 在 Java 层强制执行（不是靠 Prompt 约束模型）。
 *
 * <p>本阶段暂时不做自动 Intent 识别，intent 由调用方显式传入。
 */
@Service
public class AgentOrchestratorService {

    /** 判断用户是否明显愤怒的简单关键词。本阶段不引入情感分析模型。 */
    private static boolean isAngry(String message) {
        return message.contains("angry") || message.contains("furious") || message.contains("投诉")
                || message.contains("生气") || message.contains("糟糕");
    }

    public SpecialistPlan plan(String intent, String userMessage) {
        var normalizedIntent = intent == null ? "UNKNOWN" : intent.toUpperCase(Locale.ROOT);
        var message = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        var angry = isAngry(message);
        return switch (normalizedIntent) {
            case "ORDER_STATUS" -> new SpecialistPlan("order", "订单智能体",
                    angry ? List.of("queryOrder", "escalateToHuman") : List.of("queryOrder"),
                    "MEDIUM", true, false, angry);
            case "LOGISTICS" -> new SpecialistPlan("order", "订单/物流智能体",
                    angry ? List.of("queryOrder", "trackLogistics", "escalateToHuman")
                            : List.of("queryOrder", "trackLogistics"),
                    angry ? "HIGH" : "MEDIUM", false, false, angry);
            case "RETURN_REFUND", "CANCEL_ORDER", "ADDRESS_CHANGE" -> new SpecialistPlan("return", "退货/退款智能体",
                    List.of("queryOrder", "createReturnRequest", "requestRefundOrReplacement", "requestAddressChange", "escalateToHuman"),
                    "HIGH", true, true, true);
            case "PRODUCT_ADVICE" -> new SpecialistPlan("product", "商品顾问智能体",
                    List.of("searchProductCatalog"), "LOW", false, false, false);
            // POLICY_QA 的编排保留原项目设计；政策 RAG 工具（refundPolicyRAG）尚未复现，
            // 因此该 specialist 当前会返回“政策知识模块暂未启用”，不会伪造工具。
            case "POLICY_QA" -> new SpecialistPlan("policy_rag", "政策 RAG 智能体",
                    List.of("refundPolicyRAG"), "MEDIUM", false, false, false);
            case "COMPLAINT", "HUMAN_REQUEST" -> new SpecialistPlan("handoff", "人工交接智能体",
                    List.of("escalateToHuman"), "HIGH", false, false, true);
            default -> new SpecialistPlan("triage", "意图分流智能体",
                    List.of(), "LOW", false, false, false);
        };
    }

    /**
     * Specialist 编排结果。
     *
     * @param specialistKey                机器标识（order / return / product / policy_rag / handoff / triage）
     * @param specialistLabel              中文名称，用于 System Prompt 展示
     * @param toolAllowlist               本次请求允许使用的 Tool 名称白名单（Java 层强制过滤依据）
     * @param riskLevel                   风险等级：LOW / MEDIUM / HIGH
     * @param requiresIdentityVerification 是否需要身份核验
     * @param requiresApproval            是否涉及人工审批
     * @param recommendHumanHandoff       是否建议转人工
     */
    public record SpecialistPlan(
            String specialistKey,
            String specialistLabel,
            List<String> toolAllowlist,
            String riskLevel,
            boolean requiresIdentityVerification,
            boolean requiresApproval,
            boolean recommendHumanHandoff) {
    }
}
