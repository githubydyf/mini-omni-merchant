package com.omnimerchant.agent.dto;

/**
 * 统一收件箱（Inbox）+ 工单（Ticket）DTO。
 *
 * <p>复现自参考项目
 * {@code omni-merchant-agent/.../dto/HelpdeskDtos.java} 中与 Inbox / Ticket
 * 直接相关的部分，字段命名与顺序保持一致，便于后续按阶段补齐其余模块。
 *
 * <p>当前阶段说明：
 * <ul>
 *   <li>{@link TicketVO} 已由工单模块真实使用。</li>
 *   <li>{@link ActionRequestVO} / {@link CommerceActionPolicyVO} 已由动作审批模块真实使用。</li>
 *   <li>{@link InboxWorkItemVO} 中 {@code ticketId/latestTicketNo/latestTicketStatus}
 *       已接入真实 Ticket；{@code slaState} 仍依赖完整 SLA 模块，暂不返回；
 *       {@code assignedAgentName} 依赖身份模块，返回 null。</li>
 *   <li>{@link InboxContextVO} 的 {@code tickets} 与 {@code actions} 已接入真实数据；
 *       {@code toolCalls/sla} 依赖尚未复现的模块，返回空集合 / null，不伪造数据。</li>
 * </ul>
 * 因此 {@link SlaRiskTicketVO} / {@link InboxToolSummaryVO}
 * 仅作为结构占位声明，本阶段不会被构造。
 */
public final class HelpdeskDtos {

    private HelpdeskDtos() {
    }

    public record QueueBucketVO(
            String queueKey,
            String queueLabel,
            long count,
            String description) {
    }

    public record InboxWorkItemVO(
            String workItemType,
            Long ticketId,
            String conversationUuid,
            String customerName,
            String customerEmail,
            String channel,
            String channelLabel,
            String intent,
            String sentiment,
            Integer status,
            String statusLabel,
            Integer priority,
            Long humanAgentId,
            String assignedAgentName,
            Integer messageCount,
            Integer toolCallCount,
            java.math.BigDecimal totalCostUsd,
            String latestTicketNo,
            String latestTicketStatus,
            String slaState,
            java.time.LocalDateTime lastMessageAt,
            java.time.LocalDateTime startedAt) {
    }

    public record InboxMessageVO(
            Long id,
            String messageUuid,
            String role,
            Integer sequence,
            String content,
            String contentType,
            String originalLanguage,
            java.math.BigDecimal detectionConfidence,
            String translatedContent,
            String translationProvider,
            String translationStatus,
            Integer translationLatencyMs,
            String translationFallbackReason,
            String toolName,
            java.time.LocalDateTime createdAt) {
    }

    public record InboxCustomerContextVO(
            Long id,
            String displayName,
            String email,
            String phone,
            String countryCode,
            String language,
            String tier,
            Integer totalOrders,
            java.math.BigDecimal totalSpent,
            java.math.BigDecimal satisfaction,
            Integer blacklisted) {
    }

    public record InboxOrderContextVO(
            Long id,
            String orderNumber,
            String platform,
            String orderStatus,
            String paymentStatus,
            String fulfillmentStatus,
            String currency,
            java.math.BigDecimal totalAmount,
            java.math.BigDecimal refundedAmount,
            String trackingCarrier,
            String trackingNumber,
            String trackingStatus,
            java.time.LocalDateTime estimatedDeliveryAt,
            java.time.LocalDateTime placedAt) {
    }

    public record InboxToolSummaryVO(
            Long id,
            String traceId,
            String toolCallId,
            String toolName,
            Integer success,
            Integer latencyMs,
            String errorCode,
            String errorMessage,
            java.time.LocalDateTime createdAt) {
    }

    public record InboxContextVO(
            InboxWorkItemVO conversation,
            java.util.List<InboxMessageVO> messages,
            InboxCustomerContextVO customer,
            java.util.List<InboxOrderContextVO> recentOrders,
            java.util.List<TicketVO> tickets,
            java.util.List<ActionRequestVO> actions,
            java.util.List<InboxToolSummaryVO> toolCalls,
            SlaRiskTicketVO sla) {
    }

    public record TakeoverRequest(Long agentId, String note) {
    }

    public record HumanReplyRequest(String message, Boolean closeAfterReply, Long actorId) {
        public HumanReplyRequest(String message, Boolean closeAfterReply) {
            this(message, closeAfterReply, null);
        }
    }

    // ------------------------------------------------------------------
    // 客户诉求创建请求（ReturnRequest）。
    // 原项目这三个动作只挂在 AI 工具（OrderTools）上，没有 HTTP 入口；
    // 这里为方便后续调用补充 REST 入口，请求体字段与对应 service 方法一致。
    // ------------------------------------------------------------------

    /** 创建退货请求。 */
    public record ReturnRequestCreate(
            String orderNumber,
            String customerEmail,
            String reason,
            String items) {
    }

    /** 请求退款或补发（action = refund / replacement）。 */
    public record RefundOrReplacementCreate(
            String orderNumber,
            String customerEmail,
            String action,
            String reason) {
    }

    /** 请求修改收货地址。 */
    public record AddressChangeCreate(
            String orderNumber,
            String customerEmail,
            String newAddress) {
    }

    // ------------------------------------------------------------------
    // 以下 record 仅用于保持 InboxContextVO 的结构与原项目一致。
    // 依赖的 Ticket / Action / SLA 模块尚未复现，本阶段不会产生任何数据。
    // ------------------------------------------------------------------

    public record TicketVO(
            Long id,
            String ticketNo,
            String conversationUuid,
            String sourceType,
            Long sourceId,
            String channel,
            String customerEmail,
            String subject,
            String summary,
            String intent,
            Integer priority,
            String status,
            String statusLabel,
            Long assignedAgentId,
            String assignedAgentName,
            java.time.LocalDateTime assignedAt,
            java.time.LocalDateTime firstResponseAt,
            java.time.LocalDateTime resolvedAt,
            java.time.LocalDateTime closedAt,
            java.time.LocalDateTime slaResponseDueAt,
            java.time.LocalDateTime slaResolveDueAt,
            String slaState,
            Integer csatScore,
            String closeReason,
            String tags,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt) {
    }

    public record ActionRequestVO(
            String source,
            Long id,
            String requestNo,
            String actionType,
            String status,
            String statusLabel,
            String externalOrderNumber,
            String customerEmail,
            String amount,
            String currency,
            String riskReason,
            String requestedPayload,
            String resolution,
            String resolutionNote,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt) {
    }

    public record SlaRiskTicketVO(
            Long id,
            String ticketNo,
            String conversationUuid,
            Integer priority,
            Integer status,
            String statusLabel,
            String slaState,
            java.time.LocalDateTime responseDueAt,
            java.time.LocalDateTime resolveDueAt,
            Long assignedAgentId,
            String summary) {
    }

    /**
     * 高风险电商动作的审批闸门策略（原项目 CommerceActionPolicyVO）。
     */
    public record CommerceActionPolicyVO(
            Long id,
            String actionType,
            Integer approvalRequired,
            String minApproverRole,
            String amountThreshold,
            Integer requiresIdentityVerification,
            Integer idempotencyWindowMinutes,
            Integer externalWriteEnabled,
            String policyNote,
            Integer active) {
    }

    /**
     * 工单动作决策请求（解决/审批等）。与原项目一致；actorId 当前开发期为占位。
     */
    public record ActionDecisionRequest(Long actorId, String note) {
    }
}
