package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.entity.AgentConversationState;
import com.omnimerchant.agent.entity.AgentStateTransition;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.mapper.AgentConversationStateMapper;
import com.omnimerchant.agent.mapper.AgentStateTransitionMapper;
import com.omnimerchant.agent.mapper.ConversationMapper;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 执行状态机。
 *
 * <p>复现自参考项目 {@code service/AgentStateMachineService}，保留其设计：
 * <ul>
 *   <li>9 个状态与 {@code ALLOWED} 合法转换映射；</li>
 *   <li>{@code startRun} → {@code AI_TRIAGE} → {@code AI_WORKING}；</li>
 *   <li>{@code completeRun} / {@code failRun} 只在 {@code AI_WORKING} 时收尾到 {@code WAITING_CUSTOMER}；</li>
 *   <li>{@code toolSucceeded} 依据<b>结构化工具结果</b>进入 {@code NEEDS_APPROVAL} / {@code NEEDS_CUSTOMER_VERIFY} / {@code HUMAN_ASSIGNED}；</li>
 *   <li>状态更新与转换历史在同一事务，{@code version} 乐观锁 CAS。</li>
 * </ul>
 *
 * <p><b>与参考项目的两处必要差异</b>：
 * <ol>
 *   <li>{@code HUMAN_ASSIGNED} 不再允许回到 {@code AI_TRIAGE}：本项目已有人工接管机制，
 *       不允许普通 Chat 请求把已升级人工的会话擅自切回 AI（人工交还 AI 尚未实现）。</li>
 *   <li>{@code startRun} 增加 {@code Conversation.status} 校验：会话已升级人工 / 人工处理中 /
 *       已关闭时，AI 一律不得启动；租户来自已验证的 Conversation，不使用 0 兜底。</li>
 * </ol>
 *
 * <p><b>与 AgentRun 的区别</b>：{@code AgentRun.status}（RUNNING/SUCCESS/FAILED）属于单次运行维度，
 * 本状态机的 {@code WAITING_CUSTOMER} 不等于 {@code AgentRun.SUCCESS}，两者不可混用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStateMachineService {

    public static final String NEW = "NEW";
    public static final String AI_TRIAGE = "AI_TRIAGE";
    public static final String AI_WORKING = "AI_WORKING";
    public static final String NEEDS_CUSTOMER_VERIFY = "NEEDS_CUSTOMER_VERIFY";
    public static final String NEEDS_APPROVAL = "NEEDS_APPROVAL";
    public static final String HUMAN_ASSIGNED = "HUMAN_ASSIGNED";
    public static final String WAITING_CUSTOMER = "WAITING_CUSTOMER";
    public static final String RESOLVED = "RESOLVED";
    public static final String CLOSED = "CLOSED";

    /** 合法状态转换映射（按任务 §12；HUMAN_ASSIGNED 不允许回到 AI_TRIAGE）。 */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            NEW, Set.of(AI_TRIAGE, CLOSED),
            AI_TRIAGE, Set.of(AI_WORKING, NEEDS_CUSTOMER_VERIFY, HUMAN_ASSIGNED),
            AI_WORKING, Set.of(AI_TRIAGE, NEEDS_CUSTOMER_VERIFY, NEEDS_APPROVAL,
                    HUMAN_ASSIGNED, WAITING_CUSTOMER, RESOLVED),
            NEEDS_CUSTOMER_VERIFY, Set.of(AI_TRIAGE, AI_WORKING, HUMAN_ASSIGNED, CLOSED),
            NEEDS_APPROVAL, Set.of(AI_TRIAGE, AI_WORKING, HUMAN_ASSIGNED, RESOLVED, CLOSED),
            HUMAN_ASSIGNED, Set.of(WAITING_CUSTOMER, RESOLVED, CLOSED),
            WAITING_CUSTOMER, Set.of(AI_TRIAGE, AI_WORKING, RESOLVED, CLOSED),
            RESOLVED, Set.of(AI_TRIAGE, CLOSED),
            CLOSED, Set.of()
    );

    /** 会产生人工审批申请的工具。 */
    private static final Set<String> APPROVAL_TOOLS = Set.of(
            "createReturnRequest", "requestRefundOrReplacement", "requestAddressChange");
    private static final String TOOL_ESCALATE = "escalateToHuman";

    private static final String STATUS_PENDING_APPROVAL = "PENDING_HUMAN_APPROVAL";
    private static final String STATUS_IDENTITY_REQUIRED = "IDENTITY_VERIFICATION_REQUIRED";

    /** AI 允许处理的 conversation.status：1 进行中(AI) / 2 已完成。 */
    private static final Set<Integer> AI_ELIGIBLE_CONVERSATION_STATUS = Set.of(1, 2);

    private final AgentConversationStateMapper stateMapper;
    private final AgentStateTransitionMapper transitionMapper;
    private final ConversationMapper conversationMapper;
    private final ObjectMapper objectMapper;

    // ==================================================================
    // 生命周期
    // ==================================================================

    /**
     * 开始一轮 AI 运行：{@code NEW/可继续状态 → AI_TRIAGE → AI_WORKING}。
     *
     * <p>会先校验 {@code Conversation.status}：已升级人工 / 人工处理中 / 已关闭时直接拒绝，
     * 保证普通 Chat 请求无法绕过人工接管。
     */
    @Transactional
    public void startRun(Long tenantId, String conversationUuid, String traceId, String specialist) {
        requireTenant(tenantId);
        ensureConversationAllowsAi(tenantId, conversationUuid);

        var current = getOrCreate(tenantId, conversationUuid);
        if (CLOSED.equals(current.getState())) {
            throw new IllegalStateException("会话状态为 CLOSED，拒绝再次启动 Agent");
        }
        transition(current, AI_TRIAGE, traceId, "SUPERVISOR", specialist, "新一轮客户消息");
        transition(current, AI_WORKING, traceId, "ROUTER", specialist, "已选定处理角色");
    }

    /**
     * 正常回复完成：仅在 {@code AI_WORKING} 时收尾到 {@code WAITING_CUSTOMER}。
     *
     * <p>如果本轮已经真实创建审批申请（{@code NEEDS_APPROVAL}）或人工升级
     * （{@code HUMAN_ASSIGNED}），当前状态已不是 {@code AI_WORKING}，不会被本方法覆盖。
     */
    @Transactional
    public void completeRun(Long tenantId, String conversationUuid, String traceId) {
        requireTenant(tenantId);
        var current = find(tenantId, conversationUuid);
        if (current != null && AI_WORKING.equals(current.getState())) {
            transition(current, WAITING_CUSTOMER, traceId, "RESPONSE", "assistant_response",
                    "回复已交付，等待客户继续");
        }
    }

    /**
     * 本轮失败：仅在 {@code AI_WORKING} 时收尾到 {@code WAITING_CUSTOMER}，并记录真实失败原因。
     *
     * <p>若本轮已成功创建审批申请或人工升级，不会被失败收尾撤销。
     * {@code AgentRun.status = FAILED} 仍由 {@code AgentTraceService} 独立负责。
     */
    @Transactional
    public void failRun(Long tenantId, String conversationUuid, String traceId, String reason) {
        requireTenant(tenantId);
        var current = find(tenantId, conversationUuid);
        if (current != null && AI_WORKING.equals(current.getState())) {
            transition(current, WAITING_CUSTOMER, traceId, "FAILURE", "agent_failure", reason);
        }
    }

    /**
     * 工具成功返回后通知状态机。
     *
     * <p>严格依据<b>结构化结果</b>判断（解析 JSON 的 {@code status} 字段），
     * 不做模糊字符串匹配，也不根据 SpecialistPlan 的布尔标记臆断。
     *
     * @param output 工具返回的 JSON 字符串（业务结果对象序列化结果）
     */
    @Transactional
    public void toolSucceeded(Long tenantId, String conversationUuid, String traceId,
                              String toolName, String output) {
        requireTenant(tenantId);
        var current = find(tenantId, conversationUuid);
        if (current == null) {
            throw new IllegalStateException("会话状态不存在，无法记录工具结果");
        }
        // 只有处于 AI_WORKING 才接受工具结果推进状态
        if (!AI_WORKING.equals(current.getState())) {
            return;
        }
        var status = extractStatus(output);
        if (status == null) {
            // 无法从结构化结果中读出状态：不臆断，不产生转换
            log.warn("工具 {} 的结果无法解析 status，跳过状态更新：traceId={}", toolName, traceId);
            return;
        }

        // 1. 真实创建了待人工审批申请
        if (APPROVAL_TOOLS.contains(toolName) && STATUS_PENDING_APPROVAL.equals(status)) {
            transition(current, NEEDS_APPROVAL, traceId, "TOOL", toolName, "已创建待人工审批申请");
            return;
        }
        // 2. 身份核验未通过
        if (STATUS_IDENTITY_REQUIRED.equals(status)) {
            transition(current, NEEDS_CUSTOMER_VERIFY, traceId, "TOOL", toolName, "需要客户完成身份核验");
            return;
        }
        // 3. 真实创建人工升级（UNAVAILABLE / MISSING_TENANT_CONTEXT 不算成功）
        if (TOOL_ESCALATE.equals(toolName) && !isFailureStatus(status)) {
            transition(current, HUMAN_ASSIGNED, traceId, "TOOL", toolName, "已创建人工升级工单");
        }
    }

    /** 当前状态；无记录时视为 {@code NEW}。 */
    public String currentState(Long tenantId, String conversationUuid) {
        requireTenant(tenantId);
        var current = find(tenantId, conversationUuid);
        return current == null ? NEW : current.getState();
    }

    /** 最近若干条状态转换历史（只读，供开发期查询接口）。 */
    public List<AgentStateTransition> recentTransitions(Long tenantId, String conversationUuid, int limit) {
        requireTenant(tenantId);
        return transitionMapper.selectList(new LambdaQueryWrapper<AgentStateTransition>()
                .eq(AgentStateTransition::getTenantId, tenantId)
                .eq(AgentStateTransition::getConversationUuid, conversationUuid)
                .orderByDesc(AgentStateTransition::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 50))));
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    /**
     * 读取会话状态行，不存在则创建 {@code NEW}。
     *
     * <p>并发首次创建会竞争唯一键 {@code uk_agent_conversation_state}：捕获
     * {@link DuplicateKeyException} 后重新读取，避免"先查不存在再插入"的竞态。
     */
    private AgentConversationState getOrCreate(Long tenantId, String conversationUuid) {
        var current = find(tenantId, conversationUuid);
        if (current != null) {
            return current;
        }
        var created = new AgentConversationState();
        created.setTenantId(tenantId);
        created.setConversationUuid(conversationUuid);
        created.setState(NEW);
        created.setVersion(0);
        created.setCreatedAt(LocalDateTime.now());
        created.setUpdatedAt(LocalDateTime.now());
        try {
            stateMapper.insert(created);
            return created;
        } catch (DuplicateKeyException e) {
            // 并发创建：另一个请求已插入，重读即可
            var existing = find(tenantId, conversationUuid);
            if (existing == null) {
                throw e;
            }
            return existing;
        }
    }

    private AgentConversationState find(Long tenantId, String conversationUuid) {
        if (conversationUuid == null || conversationUuid.isBlank()) {
            throw new IllegalArgumentException("conversationUuid 不能为空");
        }
        return stateMapper.selectOne(new LambdaQueryWrapper<AgentConversationState>()
                .eq(AgentConversationState::getTenantId, tenantId)
                .eq(AgentConversationState::getConversationUuid, conversationUuid)
                .last("LIMIT 1"));
    }

    /**
     * 执行一次状态转换：校验合法性 → 乐观锁更新 → 追加转换历史。
     *
     * <p>调用方必须处于 {@code @Transactional} 中，保证"状态更新"与"历史插入"要么都成功、要么都回滚。
     */
    private void transition(AgentConversationState current, String target, String traceId,
                            String triggerType, String triggerName, String reason) {
        var source = current.getState();
        if (target.equals(source)) {
            // 已是目标状态，不产生转换（避免重复记录）
            return;
        }
        if (!ALLOWED.getOrDefault(source, Set.of()).contains(target)) {
            throw new IllegalStateException("非法的会话状态转换：" + source + " -> " + target);
        }

        var originalVersion = current.getVersion() == null ? 0 : current.getVersion();
        var redactedReason = redact(reason, 256);
        var update = new UpdateWrapper<AgentConversationState>()
                .eq("id", current.getId())
                .eq("tenant_id", current.getTenantId())
                .eq("version", originalVersion)
                .set("state", target)
                .set("last_trace_id", traceId)
                .set("last_reason", redactedReason)
                .set("version", originalVersion + 1)
                .set("updated_at", LocalDateTime.now());
        if (stateMapper.update(null, update) != 1) {
            // 影响行数不为 1 = 并发修改，拒绝本次转换（事务回滚）
            throw new IllegalStateException("会话状态已被并发修改，本次转换被拒绝");
        }
        current.setState(target);
        current.setLastTraceId(traceId);
        current.setLastReason(redactedReason);
        current.setVersion(originalVersion + 1);

        var transition = new AgentStateTransition();
        transition.setTenantId(current.getTenantId());
        transition.setConversationUuid(current.getConversationUuid());
        transition.setTraceId(traceId);
        transition.setFromState(source);
        transition.setToState(target);
        transition.setTriggerType(triggerType);
        transition.setTriggerName(triggerName);
        transition.setReasonRedacted(redact(reason, 512));
        transition.setCreatedAt(LocalDateTime.now());
        transitionMapper.insert(transition);
    }

    /** 校验会话业务状态允许 AI 处理（conversation.status ∈ {1,2}）。 */
    private void ensureConversationAllowsAi(Long tenantId, String conversationUuid) {
        var conversation = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getConversationUuid, conversationUuid)
                .last("LIMIT 1"));
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在，无法启动 Agent");
        }
        if (!tenantId.equals(conversation.getTenantId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "租户与会话不一致，拒绝启动 Agent");
        }
        var status = conversation.getStatus();
        if (status == null || !AI_ELIGIBLE_CONVERSATION_STATUS.contains(status)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "当前会话已由人工接管或已关闭，AI 不再继续处理");
        }
    }

    /** 从结构化工具结果中读取 status 字段；无法解析返回 null（不臆断）。 */
    private String extractStatus(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(output);
            var statusNode = node.get("status");
            return statusNode == null || statusNode.isNull() ? null : statusNode.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isFailureStatus(String status) {
        return "UNAVAILABLE".equals(status) || "MISSING_TENANT_CONTEXT".equals(status);
    }

    /**
     * 校验租户上下文：必须与传入 tenantId 一致。
     *
     * <p>Reactor 流式执行不在请求线程，调用方需通过 {@code CallScope.runInScope()} 绑定
     * 已验证的 Conversation.tenantId；绝不使用 0 作为缺失兜底。
     */
    private void requireTenant(Long tenantId) {
        var contextTenant = TenantContextHolder.get();
        if (tenantId == null || contextTenant == null || !tenantId.equals(contextTenant)) {
            throw new SecurityException("状态变更需要经过验证的租户上下文");
        }
    }

    private String redact(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        var redacted = value
                .replaceAll("(?i)(sk-[A-Za-z0-9_.-]{8,})", "[apikey]")
                .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[email]")
                .replaceAll("\\b(?:\\d[ -]*?){13,19}\\b", "[card]")
                .replaceAll("\\+?\\d[\\d\\s().-]{7,}\\b", "[phone]");
        return redacted.length() <= maxLength ? redacted : redacted.substring(0, maxLength);
    }
}
