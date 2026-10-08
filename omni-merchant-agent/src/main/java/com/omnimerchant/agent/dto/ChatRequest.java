package com.omnimerchant.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 流式对话接口请求体。
 *
 * <p>复现自参考项目 {@code dto/ChatRequest}。字段名与语义保持一致：
 * {@code conversationUuid} 与 {@code message} 必填，{@code message} 最长 2000 字符；
 * {@code intent} 由调用方显式传入（本阶段不做自动意图识别）。
 *
 * @param conversationUuid 会话 UUID（必须是数据库中真实存在的会话）
 * @param message          用户消息原文
 * @param intent           显式意图；为空时按 UNCLEAR 处理（最终会落到 triage、无可用工具）
 */
public record ChatRequest(
        @NotBlank(message = "conversationUuid 不能为空") String conversationUuid,
        @NotBlank(message = "message 不能为空") @Size(max = 2000, message = "message 长度不能超过 2000 字符") String message,
        String intent) {
}
