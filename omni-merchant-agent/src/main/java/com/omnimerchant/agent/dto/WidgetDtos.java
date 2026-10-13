package com.omnimerchant.agent.dto;

/**
 * Widget 买家在线客服相关请求 / 响应 DTO。
 *
 * <p>复现自参考项目 {@code CommerceDtos} 中 Widget 部分，保持字段名与前端一致。
 */
public final class WidgetDtos {

    private WidgetDtos() {
    }

    /**
     * 创建买家会话请求。
     *
     * @param tenantCode    店铺编码（必填，服务端据此确认真实租户）
     * @param customerEmail 下单邮箱（可选，仅登记联系信息，不做身份核验）
     * @param customerName  称呼（可选）
     * @param language      语言（本阶段仅登记，不做自动翻译）
     */
    public record WidgetSessionRequest(
            String tenantCode,
            String customerEmail,
            String customerName,
            String language) {
    }

    /**
     * 创建买家会话响应。
     *
     * @param customerSessionToken 合法签名的 Widget 买家令牌（仅能访问本会话）
     */
    public record WidgetSessionResponse(
            Long tenantId,
            String tenantCode,
            String conversationUuid,
            String storeName,
            String welcomeMessage,
            String customerSessionToken,
            String expiresAt) {
    }

    /**
     * 买家对话请求。{@code tenantCode} / {@code conversationUuid} 必须与令牌一致（否则 403）。
     * {@code intent} 仅为旧前端兼容字段，服务端始终忽略，自动识别意图。
     */
    public record WidgetChatRequest(
            String tenantCode,
            String conversationUuid,
            String message,
            String intent) {
    }

    /** 只包含买家可见内容，不返回工具、操作人及模型内部信息。 */
    public record WidgetMessage(String messageUuid, Integer seqNo, String role, String content,
                                java.time.LocalDateTime createdAt, String source) {}

    public record WidgetMessagesResponse(java.util.List<WidgetMessage> messages, boolean hasMore,
                                         Integer conversationStatus) {}
}
