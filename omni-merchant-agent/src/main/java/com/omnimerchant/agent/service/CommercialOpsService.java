package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.omnimerchant.agent.dto.CommerceDtos;
import com.omnimerchant.agent.dto.HelpdeskDtos;
import com.omnimerchant.agent.entity.ChatMessage;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.entity.Customer;
import com.omnimerchant.agent.entity.OrderInfo;
import com.omnimerchant.agent.entity.Ticket;
import com.omnimerchant.agent.mapper.ChatMessageMapper;
import com.omnimerchant.agent.mapper.ConversationMapper;
import com.omnimerchant.agent.mapper.CustomerMapper;
import com.omnimerchant.agent.mapper.OrderInfoMapper;
import com.omnimerchant.agent.mapper.TicketMapper;
import com.omnimerchant.agent.support.DevActor;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 统一收件箱（Inbox）+ 工单（Ticket）业务服务。
 *
 * <p>复现自参考项目 {@code CommercialOpsService} 中与 Inbox / Ticket 直接相关的部分，
 * 只保留当前已具备真实数据的能力（队列、会话列表、上下文、人工接管、人工回复、
 * 工单查询 / 接管 / 解决）。
 *
 * <p>去除的部分（原项目依赖、当前尚未复现的模块）：SLA 完整模块、Action 审批、
 * ToolCallLog、SupportMacro、客服身份查询、QA、Audit、多租户上下文。
 * 对应能力暂不实现；Ticket / EscalationRecord 中的 SLA 字段仍按原结构保留，
 * 待 SLA 模块复现后再启用。
 */
@Service
@RequiredArgsConstructor
public class CommercialOpsService {

    private final ConversationMapper conversationMapper;
    private final CustomerMapper customerMapper;
    private final OrderInfoMapper orderInfoMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final TicketMapper ticketMapper;
    private final CommerceApprovalService commerceApprovalService;

    // ==================================================================
    // 队列：基于 conversation.status 生成，不新建 inbox_queue 表。
    // approval（动作审批）队列已接入真实 pendingCount；
    // sla_risk（SLA 工单）依赖尚未复现的 SLA 模块，本阶段不对外暴露。
    // ==================================================================
    public List<HelpdeskDtos.QueueBucketVO> inboxQueues() {
        var conversations = conversationMapper.selectList(new LambdaQueryWrapper<Conversation>().last("LIMIT 2000"));
        return List.of(
                bucket("unassigned", "待分配", queueCount(conversations, "unassigned"), "AI 已升级但还没有客服接管"),
                bucket("mine", "我的处理中", queueCount(conversations, "mine"), "已由人工客服接管的会话"),
                bucket("ai", "AI 处理中", queueCount(conversations, "ai"), "仍由 AI 处理的买家会话"),
                bucket("waiting_customer", "待客户回复", queueCount(conversations, "waiting_customer"), "AI 或人工已回复，等待买家补充"),
                bucket("approval", "待审批", commerceApprovalService.pendingCount(), "退款、补发、改地址等高风险动作"),
                bucket("resolved", "已解决", queueCount(conversations, "resolved"), "已关闭或已解决的会话")
        );
    }

    // ==================================================================
    // 会话列表：基于 conversation，按 lastMessageAt 倒序，内存分页。
    // 原项目这里还会合并 Ticket 来源；本阶段只返回 Conversation 来源。
    // ==================================================================
    public CommerceDtos.PageResult<HelpdeskDtos.InboxWorkItemVO> inboxItems(String queue, int page, int size) {
        var queueKey = valueOr(queue, "all");
        var conversations = conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .orderByDesc(Conversation::getLastMessageAt)
                .orderByDesc(Conversation::getStartedAt)
                .last("LIMIT 1000"));

        var sorted = conversations.stream()
                .filter(c -> conversationMatchesQueue(c, queueKey))
                .map(this::toInboxItem)
                .sorted(Comparator.comparing(HelpdeskDtos.InboxWorkItemVO::lastMessageAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        var from = Math.max(0, (page - 1) * clamp(size));
        var to = Math.min(sorted.size(), from + clamp(size));
        return new CommerceDtos.PageResult<>(sorted.size(), from >= sorted.size() ? List.of() : sorted.subList(from, to));
    }

    // ==================================================================
    // 上下文：真实聚合 Conversation / ChatMessage / Customer / OrderInfo / Ticket。
    // 原设计中的 actions / toolCalls / sla 依赖尚未复现的模块，返回空集 / null。
    // ==================================================================
    public HelpdeskDtos.InboxContextVO inboxContext(String conversationUuid) {
        var conversation = requireConversation(conversationUuid);
        var messages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationUuid, conversationUuid)
                        .orderByAsc(ChatMessage::getSeqNo)
                        .orderByAsc(ChatMessage::getCreatedAt))
                .stream().map(this::toInboxMessageVO).toList();

        Customer customer = null;
        if (conversation.getCustomerId() != null) {
            customer = customerMapper.selectById(conversation.getCustomerId());
        }
        if (customer == null && conversation.getCustomerEmail() != null) {
            customer = customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
                    .eq(Customer::getEmail, conversation.getCustomerEmail())
                    .last("LIMIT 1"));
        }

        var ordersWrapper = new LambdaQueryWrapper<OrderInfo>()
                .orderByDesc(OrderInfo::getPlacedAt)
                .last("LIMIT 5");
        if (customer != null && customer.getId() != null) {
            ordersWrapper.eq(OrderInfo::getCustomerId, customer.getId());
        } else if (conversation.getCustomerEmail() != null) {
            ordersWrapper.eq(OrderInfo::getCustomerEmail, conversation.getCustomerEmail());
        } else {
            ordersWrapper.eq(OrderInfo::getId, -1L);
        }

        var tickets = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                        .eq(Ticket::getConversationUuid, conversationUuid)
                        .orderByDesc(Ticket::getUpdatedAt))
                .stream().map(this::toTicketVO).toList();

        // 真实聚合：按 relatedOrderId 优先 / 否则 customerEmail 关联审批请求
        var actions = commerceApprovalService.forConversation(conversation);

        return new HelpdeskDtos.InboxContextVO(
                toInboxItem(conversation),
                messages,
                toInboxCustomerContextVO(customer),
                orderInfoMapper.selectList(ordersWrapper).stream().map(this::toInboxOrderContextVO).toList(),
                tickets,
                actions,
                List.of(),
                null);
    }

    // ==================================================================
    // 人工接管：当前没有登录用户 / 客服分配模块，因此只更新 conversation 中
    // 能够表示“人工处理中”的字段（status / escalated / escalatedAt），
    // 不设置 humanAgentId，不写审计。
    // ==================================================================
    @Transactional
    public HelpdeskDtos.InboxWorkItemVO takeover(String conversationUuid, HelpdeskDtos.TakeoverRequest request) {
        var conversation = requireConversation(conversationUuid);
        if (Integer.valueOf(5).equals(conversation.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已关闭会话不能接管");
        }
        conversation.setStatus(4);
        conversation.setEscalated(1);
        conversation.setEscalatedAt(conversation.getEscalatedAt() == null ? LocalDateTime.now() : conversation.getEscalatedAt());
        conversationMapper.updateById(conversation);
        return toInboxItem(conversation);
    }

    // ==================================================================
    // 人工回复：真正向 chat_message 新增一条 assistant 消息，并同步更新
    // conversation 的 seqNo / messageCount / lastMessageAt / status（/ endedAt）。
    // 写操作使用事务。
    // ==================================================================
    @Transactional
    public HelpdeskDtos.InboxWorkItemVO humanReply(String conversationUuid, HelpdeskDtos.HumanReplyRequest request) {
        var conversation = requireConversation(conversationUuid);
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "人工回复不能为空");
        }
        // 没有登录身份，用“是否已人工接管（status=4）”代替原项目的 humanAgentId 校验
        if (!Integer.valueOf(4).equals(conversation.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "请先接管会话后再回复");
        }
        var seq = chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationUuid, conversationUuid)).intValue() + 1;
        var message = new ChatMessage();
        message.setTenantId(conversation.getTenantId());
        message.setConversationId(conversation.getId());
        message.setConversationUuid(conversationUuid);
        message.setMessageUuid(UUID.randomUUID().toString());
        message.setRole("assistant");
        message.setSeqNo(seq);
        message.setContent(request.message().trim());
        message.setContentType("TEXT");
        message.setOriginalLang(conversation.getLanguage());
        message.setPromptTokens(0);
        message.setCompletionTokens(0);
        message.setTotalTokens(0);
        message.setCostUsd(BigDecimal.ZERO);
        message.setIsStreamed(0);
        chatMessageMapper.insert(message);
        conversation.setMessageCount((conversation.getMessageCount() == null ? 0 : conversation.getMessageCount()) + 1);
        conversation.setLastMessageAt(LocalDateTime.now());
        if (Boolean.TRUE.equals(request.closeAfterReply())) {
            conversation.setStatus(5);
            conversation.setResolved(1);
            conversation.setEndedAt(LocalDateTime.now());
            conversation.setDurationSeconds(durationSeconds(conversation.getStartedAt(), conversation.getEndedAt()));
        } else {
            conversation.setStatus(2);
        }
        conversationMapper.updateById(conversation);
        return toInboxItem(conversation);
    }

    // ==================================================================
    // 工单（Ticket）：查询 / 接管 / 解决。逻辑与原项目 CommercialOpsService 一致。
    // status 可选过滤，ORDER BY priority DESC, updatedAt DESC，返回分页 TicketVO。
    // ==================================================================
    public CommerceDtos.PageResult<HelpdeskDtos.TicketVO> tickets(String status, int page, int size) {
        var wrapper = new LambdaQueryWrapper<Ticket>()
                .eq(status != null && !status.isBlank(), Ticket::getStatus, status)
                .orderByDesc(Ticket::getPriority)
                .orderByDesc(Ticket::getUpdatedAt);
        var result = ticketMapper.selectPage(new Page<>(page, clamp(size)), wrapper);
        return new CommerceDtos.PageResult<>(result.getTotal(),
                result.getRecords().stream().map(this::toTicketVO).toList());
    }

    @Transactional
    public HelpdeskDtos.TicketVO assignTicket(Long id, HelpdeskDtos.TakeoverRequest request) {
        var ticket = requireTicket(id);
        var actorId = DevActor.resolve(request == null ? null : request.agentId());
        if (List.of("RESOLVED", "CLOSED").contains(ticket.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已结束工单不能重新分配");
        }
        ticket.setAssignedAgentId(actorId);
        ticket.setAssignedAt(LocalDateTime.now());
        ticket.setStatus("ASSIGNED");
        ticketMapper.updateById(ticket);
        // TODO 恢复登录鉴权与 Audit 模块后，写入 support 审计记录（原项目 supportAuditService.record）。
        return toTicketVO(ticket);
    }

    @Transactional
    public HelpdeskDtos.TicketVO resolveTicket(Long id, HelpdeskDtos.ActionDecisionRequest request) {
        var ticket = requireTicket(id);
        if (List.of("RESOLVED", "CLOSED").contains(ticket.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "工单已经结束");
        }
        ticket.setStatus("RESOLVED");
        ticket.setResolvedAt(LocalDateTime.now());
        ticket.setClosedAt(LocalDateTime.now());
        ticket.setCloseReason(request == null || request.note() == null ? "RESOLVED" : request.note());
        ticketMapper.updateById(ticket);
        // TODO QA 模块复现后，恢复 helpdeskProjectionService.enqueueForTicket(ticket)。
        // TODO Audit 模块复现后，恢复 supportAuditService.record(...)。
        return toTicketVO(ticket);
    }

    // ==================================================================
    // 映射
    // ==================================================================

    private HelpdeskDtos.TicketVO toTicketVO(Ticket t) {
        return new HelpdeskDtos.TicketVO(t.getId(), t.getTicketNo(), t.getConversationUuid(), t.getSourceType(),
                t.getSourceId(), t.getChannel(), t.getCustomerEmail(), t.getSubject(), t.getSummary(), t.getIntent(),
                t.getPriority(), t.getStatus(), ticketStatusLabel(t.getStatus()), t.getAssignedAgentId(),
                // assignedAgentName 依赖客服身份模块，尚未复现，返回 null（不伪造客服名称）。
                null, t.getAssignedAt(),
                t.getFirstResponseAt(), t.getResolvedAt(), t.getClosedAt(), t.getSlaResponseDueAt(), t.getSlaResolveDueAt(),
                t.getSlaState(), t.getCsatScore(), t.getCloseReason(), t.getTags(), t.getCreatedAt(), t.getUpdatedAt());
    }

    private HelpdeskDtos.InboxWorkItemVO toInboxItem(Conversation c) {
        // 原项目这里会带出 latestTicket 的 id / ticketNo / status / slaState，
        // 以及人工客服显示名。当前 Ticket 已复现，故补上最新工单信息；
        // slaState 依赖完整 SLA 模块、assignedAgentName 依赖身份模块，暂返回 null。
        var latestTicket = latestTicket(c.getConversationUuid());
        return new HelpdeskDtos.InboxWorkItemVO("CONVERSATION", latestTicket == null ? null : latestTicket.getId(),
                c.getConversationUuid(), c.getCustomerName(), c.getCustomerEmail(),
                c.getChannel(), channelLabel(c.getChannel()), c.getIntentPrimary(), c.getSentiment(), c.getStatus(),
                statusLabel(c.getStatus()), c.getPriority(), c.getHumanAgentId(), null,
                c.getMessageCount(), c.getToolCallCount(),
                c.getTotalCostUsd(), latestTicket == null ? null : latestTicket.getTicketNo(),
                latestTicket == null ? null : ticketStatusLabel(latestTicket.getStatus()),
                null,
                c.getLastMessageAt(), c.getStartedAt());
    }

    private Ticket latestTicket(String conversationUuid) {
        if (conversationUuid == null || conversationUuid.isBlank()) {
            return null;
        }
        return ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getConversationUuid, conversationUuid)
                .orderByDesc(Ticket::getCreatedAt)
                .last("LIMIT 1"));
    }

    private HelpdeskDtos.InboxMessageVO toInboxMessageVO(ChatMessage message) {
        return new HelpdeskDtos.InboxMessageVO(message.getId(), message.getMessageUuid(), message.getRole(),
                message.getSeqNo(), message.getContent(), message.getContentType(), message.getOriginalLang(),
                message.getDetectionConfidence(), message.getTranslatedContent(), message.getTranslationProvider(),
                message.getTranslationStatus(), message.getTranslationLatencyMs(), message.getTranslationFallbackReason(),
                message.getToolName(), message.getCreatedAt());
    }

    private HelpdeskDtos.InboxCustomerContextVO toInboxCustomerContextVO(Customer customer) {
        if (customer == null) {
            return null;
        }
        return new HelpdeskDtos.InboxCustomerContextVO(customer.getId(), customer.getDisplayName(), customer.getEmail(),
                customer.getPhone(), customer.getCountryCode(), customer.getLanguagePref(), customer.getCustomerTier(),
                customer.getTotalOrders(), customer.getTotalSpent(), customer.getSatisfactionAvg(), customer.getIsBlacklisted());
    }

    private HelpdeskDtos.InboxOrderContextVO toInboxOrderContextVO(OrderInfo order) {
        return new HelpdeskDtos.InboxOrderContextVO(order.getId(), order.getExternalOrderNumber(), order.getPlatform(),
                order.getOrderStatus(), order.getPaymentStatus(), order.getFulfillmentStatus(), order.getCurrency(),
                order.getTotalAmount(), order.getRefundedAmount(), order.getTrackingCarrier(), order.getTrackingNumber(),
                order.getTrackingStatus(), order.getEstimatedDeliveryAt(), order.getPlacedAt());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 队列判定。原项目用 {@code humanAgentId} 区分“待分配 / 我的处理中”，
     * 当前阶段没有客服身份模块（humanAgentId 恒为 null），因此改用 status 表达：
     * 3=已升级人工(待分配)、4=人工处理中(我的处理中)，语义与原项目一致。
     */
    private boolean conversationMatchesQueue(Conversation c, String queue) {
        return switch (queue) {
            case "unassigned" -> Integer.valueOf(3).equals(c.getStatus());
            case "mine" -> Integer.valueOf(4).equals(c.getStatus());
            case "ai" -> Integer.valueOf(1).equals(c.getStatus());
            case "waiting_customer" -> Integer.valueOf(2).equals(c.getStatus());
            case "resolved" -> c.getStatus() != null && c.getStatus() >= 5;
            case "sla_risk" -> List.of(3, 4, 6).contains(c.getStatus());
            case "approval" -> false;
            default -> true;
        };
    }

    private long queueCount(List<Conversation> conversations, String queue) {
        return conversations.stream().filter(c -> conversationMatchesQueue(c, queue)).count();
    }

    private Conversation requireConversation(String conversationUuid) {
        var row = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getConversationUuid, conversationUuid)
                .last("LIMIT 1"));
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return row;
    }

    private HelpdeskDtos.QueueBucketVO bucket(String key, String label, long count, String description) {
        return new HelpdeskDtos.QueueBucketVO(key, label, count, description);
    }

    private Ticket requireTicket(Long id) {
        var row = ticketMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工单不存在");
        }
        return row;
    }

    /** 工单状态文案（原项目 ticketStatusLabel(String)）。 */
    private String ticketStatusLabel(String status) {
        return switch (valueOr(status, "OPEN")) {
            case "OPEN" -> "待分配";
            case "ASSIGNED" -> "处理中";
            case "WAITING_CUSTOMER" -> "待客户回复";
            case "PENDING_APPROVAL" -> "待审批";
            case "RESOLVED" -> "已解决";
            case "CLOSED" -> "已关闭";
            case "CANCELLED" -> "已取消";
            default -> status;
        };
    }

    /**
     * 收件箱状态文案。注意与原项目一致：收件箱视角下 status=2 表示“待客户回复”
     * （会话页 ConversationVO 中 2 表示“已完成”，两处语义不同，均照原项目保留）。
     */
    private String statusLabel(Integer status) {
        return switch (status == null ? 0 : status) {
            case 1 -> "AI处理中";
            case 2 -> "待客户回复";
            case 3 -> "已升级人工";
            case 4 -> "人工处理中";
            case 5 -> "已关闭";
            case 6 -> "已超时";
            default -> "未知";
        };
    }

    private String channelLabel(String channel) {
        return switch (valueOr(channel, "UNKNOWN")) {
            case "WEB_WIDGET", "WEB", "CHAT" -> "买家咨询组件";
            case "WECHAT_KF" -> "企业微信 / 微信客服";
            case "EMAIL" -> "邮件";
            case "WHATSAPP" -> "WhatsApp";
            case "INSTAGRAM" -> "Instagram";
            case "FACEBOOK", "MESSENGER" -> "Facebook / Messenger";
            case "SMS" -> "短信";
            case "VOICE" -> "电话";
            default -> "未知渠道";
        };
    }

    private int clamp(int size) {
        return Math.max(1, Math.min(size, 100));
    }

    private int durationSeconds(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return Math.max(0, (int) Duration.between(start, end).toSeconds());
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
