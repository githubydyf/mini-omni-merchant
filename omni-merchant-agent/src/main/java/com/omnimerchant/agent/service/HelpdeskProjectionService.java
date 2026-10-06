package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.entity.EscalationRecord;
import com.omnimerchant.agent.entity.Ticket;
import com.omnimerchant.agent.mapper.ConversationMapper;
import com.omnimerchant.agent.mapper.EscalationRecordMapper;
import com.omnimerchant.agent.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Helpdesk 投影服务：把 EscalationRecord 投影为统一的 Ticket。
 *
 * <p>复现自参考项目 {@code HelpdeskProjectionService} 的工单投影部分，
 * 保持原项目设计：EscalationRecord → Ticket，来源关系
 * {@code ticket.source_type='ESCALATION', ticket.source_id=escalation.id} 不变。
 *
 * <p>相对原项目的取舍：
 * <ul>
 *   <li>去掉 QA 相关（{@code enqueueForTicket} / {@code enqueueForConversation} /
 *       {@code backfillQaFromResolvedEscalations}）——QA 模块尚未复现。</li>
 *   <li>{@code tenantId} 改为从来源 EscalationRecord 读取（原项目取自
 *       {@code TenantContextHolder}，当前无多租户上下文）。</li>
 *   <li>补全 {@code customerEmail} 与 {@code channel}：原项目投影只写 customerId、
 *       channel 固定 "WEB_WIDGET"。为保证 Ticket 与 Conversation / Customer 的关联正确，
 *       这里从关联 Conversation 取真实 customerEmail 与 channel（取不到才回退）。</li>
 * </ul>
 *
 * <p>幂等：同一 EscalationRecord 不会重复建单 —— 先按 source 查已有 Ticket，
 * 已存在则跳过（不覆盖）。由 {@code uk_ticket_source(tenant_id, source_type, source_id)}
 * 唯一约束兜底。
 *
 * <p>与原项目的差异（重要）：原项目 backfill 对已存在的 Ticket 会整体覆盖
 * （含 {@code status}），当它被周期调度反复执行时，会把工单侧
 * {@code /tickets/{id}/assign|resolve} 改写的状态又按 EscalationRecord 的旧状态冲回去，
 * 导致 assign / resolve 形同失效。这里改为「仅创建缺失的 Ticket、不覆盖已存在的」，
 * 使投影专注其本职（由升级记录生成工单），并保证工单状态流转可持久。
 * 创建时的字段映射与状态映射（1→OPEN 2/3→ASSIGNED 4→RESOLVED 5→CLOSED 6→CANCELLED）
 * 仍与原项目一致。
 */
@Service
@RequiredArgsConstructor
public class HelpdeskProjectionService {

    private final EscalationRecordMapper escalationMapper;
    private final TicketMapper ticketMapper;
    private final ConversationMapper conversationMapper;

    @Transactional
    public void synchronize() {
        backfillTicketsFromEscalations();
    }

    private void backfillTicketsFromEscalations() {
        var rows = escalationMapper.selectList(new LambdaQueryWrapper<EscalationRecord>()
                .orderByDesc(EscalationRecord::getCreatedAt)
                .last("LIMIT 500"));
        for (var source : rows) {
            var existing = ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                    .eq(Ticket::getSourceType, "ESCALATION")
                    .eq(Ticket::getSourceId, source.getId())
                    .last("LIMIT 1"));
            if (existing != null) {
                // 已建单：不覆盖，保护工单侧的状态流转（assign / resolve）。
                continue;
            }
            var conversation = findConversation(source.getConversationUuid());
            var ticket = new Ticket();
            ticket.setTenantId(source.getTenantId());
            ticket.setSourceType("ESCALATION");
            ticket.setSourceId(source.getId());
            ticket.setTicketNo(source.getTicketNo());
            ticket.setConversationUuid(source.getConversationUuid());
            ticket.setCustomerId(source.getCustomerId());
            ticket.setCustomerEmail(conversation == null ? null : conversation.getCustomerEmail());
            // 原项目固定 WEB_WIDGET；这里优先取关联会话的真实渠道，取不到再回退。
            ticket.setChannel(conversation == null || conversation.getChannel() == null
                    ? "WEB_WIDGET" : conversation.getChannel());
            ticket.setSubject(valueOr(source.getEscalationReason(), "人工升级工单"));
            ticket.setSummary(source.getSummary());
            ticket.setIntent(source.getCustomerIntent());
            ticket.setPriority(source.getPriority());
            ticket.setStatus(ticketStatusKey(source.getStatus()));
            ticket.setAssignedAgentId(source.getAssignedAgentId());
            ticket.setAssignedAt(source.getAssignedAt());
            ticket.setFirstResponseAt(source.getFirstResponseAt());
            ticket.setResolvedAt(source.getResolvedAt());
            ticket.setClosedAt(source.getClosedAt());
            ticket.setSlaResponseDueAt(source.getSlaResponseDueAt());
            ticket.setSlaResolveDueAt(source.getSlaResolveDueAt());
            ticket.setSlaState(slaState(source));
            ticket.setCsatScore(source.getCsatScore());
            ticket.setCsatComment(source.getCsatComment());
            ticket.setCloseReason(source.getResolution());
            ticket.setTags(source.getTags());
            ticketMapper.insert(ticket);
        }
    }

    private Conversation findConversation(String conversationUuid) {
        if (conversationUuid == null || conversationUuid.isBlank()) {
            return null;
        }
        return conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getConversationUuid, conversationUuid)
                .last("LIMIT 1"));
    }

    private String slaState(EscalationRecord source) {
        var now = LocalDateTime.now();
        if (dueBefore(source.getSlaResponseDueAt(), now) || dueBefore(source.getSlaResolveDueAt(), now)
                || Integer.valueOf(1).equals(source.getSlaResponseBreached())
                || Integer.valueOf(1).equals(source.getSlaResolveBreached())) {
            return "BREACHED";
        }
        if (source.getSlaResolveDueAt() != null && source.getSlaResolveDueAt().isBefore(now.plusMinutes(30))) {
            return "DUE_SOON";
        }
        return "NORMAL";
    }

    private String ticketStatusKey(Integer status) {
        return switch (status == null ? 0 : status) {
            case 1 -> "OPEN";
            case 2, 3 -> "ASSIGNED";
            case 4 -> "RESOLVED";
            case 5 -> "CLOSED";
            case 6 -> "CANCELLED";
            default -> "OPEN";
        };
    }

    private boolean dueBefore(LocalDateTime dueAt, LocalDateTime now) {
        return dueAt != null && dueAt.isBefore(now);
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
