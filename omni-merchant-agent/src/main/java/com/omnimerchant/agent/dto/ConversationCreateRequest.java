package com.omnimerchant.agent.dto;

/**
 * 创建会话请求体（开发测试台使用）。
 *
 * <p>不包含 customerId / orderId 等身份与业务字段：当前阶段不编造客户与订单关联。
 *
 * @param tenantId      租户 ID；为空时回退到请求头 X-Tenant-Id
 * @param channel       渠道，默认 WEB
 * @param customerEmail 客户邮箱（可选，仅作为会话上的联系信息快照，不做身份核验）
 * @param customerName  客户名（可选）
 */
public record ConversationCreateRequest(
        Long tenantId,
        String channel,
        String customerEmail,
        String customerName) {
}
