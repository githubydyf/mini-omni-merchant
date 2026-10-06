package com.omnimerchant.agent.dto;

/**
 * Agent 调试接口请求体（开发阶段使用）。
 *
 * <p>本阶段 intent 由调用方显式传入，不做自动意图识别。
 */
public record AgentChatRequest(
        String conversationUuid,
        String intent,
        String message) {
}
