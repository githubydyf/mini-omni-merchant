package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.mapper.ConversationMapper;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 会话生命周期：真实创建会话 + 正式 Chat 接入前的可用性校验。
 *
 * <p>为什么需要这个类：前端此前在浏览器本地生成 UUID，数据库里并不存在对应会话，
 * 导致 chat_message 无法写入、Tool 审计无法关联。这里提供真实创建能力，
 * 并在 AI 回复前做统一校验。
 *
 * <p>校验规则（复用参考项目 conversation.status 语义）：
 * <pre>
 * 1 进行中(AI)   2 已完成       → AI 可以回复
 * 3 已升级人工    4 人工处理中   → AI 不得擅自继续回复
 * 5 已关闭       6 已超时       → AI 不得继续回复
 * </pre>
 *
 * <p><b>身份说明（开发阶段）</b>：当前未复现鉴权，请求可携带的 {@code X-Tenant-Id}
 * 仅作为一致性校验，<b>不作为可信身份</b>。以数据库会话行上的 {@code tenantId}
 * 为权威租户来源（Tool 执行使用该值），避免用户伪造租户越权。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationLifecycleService {

    /** AI 允许回复的会话状态。 */
    private static final Set<Integer> AI_ELIGIBLE_STATUS = Set.of(1, 2);

    private static final String DEFAULT_CHANNEL = "WEB";
    private static final int STATUS_AI_ACTIVE = 1;
    private static final int DEFAULT_PRIORITY = 2;

    private final ConversationMapper conversationMapper;

    /**
     * 真实创建一个新会话（用于前端"新对话"）。
     *
     * <p>只写入会话本身真实可得的字段；不编造 customer / order / 身份验证结果。
     */
    public Conversation create(Long tenantId, String channel, String customerEmail, String customerName) {
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少租户标识，无法创建会话");
        }
        var now = LocalDateTime.now();
        var conversation = new Conversation();
        conversation.setConversationUuid(UUID.randomUUID().toString());
        conversation.setTenantId(tenantId);
        conversation.setChannel(channel == null || channel.isBlank() ? DEFAULT_CHANNEL : channel.trim());
        conversation.setStatus(STATUS_AI_ACTIVE);
        conversation.setEscalated(0);
        conversation.setPriority(DEFAULT_PRIORITY);
        conversation.setMessageCount(0);
        conversation.setToolCallCount(0);
        conversation.setStartedAt(now);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversation.setIsDeleted(0);
        conversation.setVersion(0);
        if (customerEmail != null && !customerEmail.isBlank()) {
            conversation.setCustomerEmail(customerEmail.trim());
        }
        if (customerName != null && !customerName.isBlank()) {
            conversation.setCustomerName(customerName.trim());
        }
        conversationMapper.insert(conversation);
        log.info("创建会话：uuid={}, tenantId={}, channel={}",
                conversation.getConversationUuid(), tenantId, conversation.getChannel());
        return conversation;
    }

    /**
     * 按 UUID 取会话并校验 AI 是否可回复。
     *
     * @param requestedTenantId 请求携带的租户（开发阶段可空）；非空且与库中不一致时拒绝
     */
    public Conversation requireForAi(String conversationUuid, Long requestedTenantId) {
        if (conversationUuid == null || conversationUuid.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少 conversationUuid");
        }
        var conversation = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getConversationUuid, conversationUuid)
                .last("LIMIT 1"));
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在，请先创建会话");
        }
        if (requestedTenantId != null && !requestedTenantId.equals(conversation.getTenantId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "租户与会话不一致，拒绝处理");
        }
        var status = conversation.getStatus();
        if (status == null || !AI_ELIGIBLE_STATUS.contains(status)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前会话已由人工接管或已关闭，AI 不再继续回复");
        }
        return conversation;
    }
}
