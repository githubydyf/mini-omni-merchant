package com.omnimerchant.agent.escalation;

import com.omnimerchant.agent.context.CallContextHolder;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.entity.EscalationRecord;
import com.omnimerchant.agent.mapper.ConversationMapper;
import com.omnimerchant.agent.mapper.EscalationRecordMapper;
import com.omnimerchant.agent.service.HelpdeskProjectionRequestedEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Human agent escalation service.
 *
 * <p>复现自参考项目 {@code escalation/EscalationService}，核心流程不变：
 * 创建 EscalationRecord → 更新 Conversation → 发布投影事件（由
 * {@code HelpdeskProjectionService} 在事务提交后投影成 Ticket）。
 *
 * <p>提供两个入口：
 * <ul>
 *   <li>{@link #escalate(String, String, String, int)}（4 参，显式 conversationUuid）——
 *       管理端 / 测试用，tenantId 从目标会话推断。</li>
 *   <li>{@link #escalate(String, String, int)}（3 参）—— 复现原项目 Tool 的调用形态：
 *       conversationUuid 取自 {@link CallContextHolder}（Agent 调用上下文），
 *       无上下文时回退为 {@code manual-<uuid>}；tenantId 优先取 {@link TenantContextHolder}。</li>
 * </ul>
 *
 * <p>TODO 恢复 Agent 调用链后，3 参入口即为 Tool 的正式调用路径；管理端如需保留再单独评估。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EscalationService {

    private final EscalationRecordMapper escalationMapper;
    private final ConversationMapper conversationMapper;

    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    void setApplicationEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Tool 入口（复现原项目 escalate 的调用形态）：conversationUuid 取自
     * {@link CallContextHolder}，无上下文时回退为 manual-&lt;uuid&gt;；
     * tenantId 优先取目标会话，否则取 {@link TenantContextHolder}（Tool 已保证非空）。
     */
    @Transactional
    public EscalationResult escalate(String reason, String summary, int priority) {
        var callContext = CallContextHolder.get();
        var conversationUuid = callContext == null || callContext.conversationUuid() == null
                ? "manual-" + UUID.randomUUID()
                : callContext.conversationUuid();
        var tenantId = resolveTenant(conversationUuid);
        if (tenantId == null) {
            return new EscalationResult("UNAVAILABLE", 0, "MISSING_TENANT_CONTEXT",
                    "Human escalation requires a verified tenant context.");
        }
        return doEscalate(conversationUuid, tenantId, reason, summary, priority);
    }

    /** 管理端 / 测试入口：显式 conversationUuid，tenantId 从目标会话推断。 */
    @Transactional
    public EscalationResult escalate(String conversationUuid, String reason, String summary, int priority) {
        var tenantId = resolveTenant(conversationUuid);
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在，无法发起人工升级");
        }
        return doEscalate(conversationUuid, tenantId, reason, summary, priority);
    }

    private EscalationResult doEscalate(String conversationUuid, Long tenantId,
                                        String reason, String summary, int priority) {
        var record = new EscalationRecord();
        record.setTenantId(tenantId);
        record.setTicketNo(nextTicketNo());
        record.setConversationUuid(conversationUuid);
        record.setCustomerId(customerIdOf(conversationUuid));
        record.setEscalationType("AI_PROACTIVE");
        record.setEscalationReason(cleanReason(reason));
        record.setReasonDetail(reason);
        record.setSummary(summary);
        record.setCustomerIntent(intentOf(conversationUuid));
        record.setPriority(Math.max(1, Math.min(priority, 4)));
        record.setStatus(1);
        record.setSlaResponseSeconds(300);
        record.setSlaResolveSeconds(3600);
        record.setSlaResponseDueAt(LocalDateTime.now().plusMinutes(5));
        record.setSlaResolveDueAt(LocalDateTime.now().plusHours(1));
        record.setSlaResponseBreached(0);
        record.setSlaResolveBreached(0);
        record.setEscalatedBackToAi(0);
        escalationMapper.insert(record);

        markConversationEscalated(record);

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new HelpdeskProjectionRequestedEvent(tenantId));
        }
        log.info("Escalation ticket created: tenant={}, ticket={}, priority={}",
                tenantId, record.getTicketNo(), record.getPriority());
        return new EscalationResult(record.getTicketNo(), waitMinutes(record.getPriority()), "PENDING",
                "Human escalation ticket created and waiting for assignment.");
    }

    /** 优先用目标会话的 tenantId；会话不存在时回退到租户上下文（复现原项目 Tool 路径）。 */
    private Long resolveTenant(String conversationUuid) {
        var conv = findConversation(conversationUuid);
        return conv != null ? conv.getTenantId() : TenantContextHolder.get();
    }

    private Conversation findConversation(String conversationUuid) {
        if (conversationUuid == null || conversationUuid.isBlank()) {
            return null;
        }
        return conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getConversationUuid, conversationUuid)
                .last("LIMIT 1"));
    }

    private Long customerIdOf(String conversationUuid) {
        var conv = findConversation(conversationUuid);
        return conv == null ? null : conv.getCustomerId();
    }

    private String intentOf(String conversationUuid) {
        var conv = findConversation(conversationUuid);
        return conv == null ? null : conv.getIntentPrimary();
    }

    private void markConversationEscalated(EscalationRecord record) {
        var conv = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getConversationUuid, record.getConversationUuid()));
        if (conv == null) {
            return;
        }
        conv.setEscalated(1);
        conv.setStatus(3);
        conv.setEscalationReason(record.getEscalationReason());
        conv.setEscalatedAt(LocalDateTime.now());
        conv.setPriority(record.getPriority());
        conversationMapper.updateById(conv);
    }

    private String nextTicketNo() {
        return "TKT-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String cleanReason(String reason) {
        var normalized = reason == null || reason.isBlank()
                ? "USER_REQUEST"
                : reason.toUpperCase(Locale.ROOT).replace(' ', '_');
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private int waitMinutes(int priority) {
        return switch (priority) {
            case 4 -> 2;
            case 3 -> 5;
            case 2 -> 10;
            default -> 20;
        };
    }
}
