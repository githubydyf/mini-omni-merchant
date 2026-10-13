package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.omnimerchant.agent.entity.ChatMessage;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.mapper.ChatMessageMapper;
import com.omnimerchant.agent.mapper.ConversationMapper;
import com.omnimerchant.agent.dto.WidgetDtos;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 会话消息持久化（Chat 专用）。
 *
 * <p>独立于 Controller，Controller 不直接访问 Mapper。
 *
 * <p><b>并发安全</b>：chat_message 存在唯一键 {@code uk_conv_seq(conversation_id, seq_no)}，
 * 不能简单"取最大值加 1"。这里用两种手段共同保证：
 * <ol>
 *   <li>先用 {@code SELECT ... FOR UPDATE} 锁住会话行（InnoDB 行锁），串行化同一会话的序号分配；</li>
 *   <li>仍可能出现极端并发/锁未命中的情况，靠唯一键兜底：捕获 {@link DuplicateKeyException}
 *       后重试若干次重新分配 seqNo。</li>
 * </ol>
 *
 * <p><b>事务边界</b>：本类所有写方法都是独立短事务，只覆盖数据库操作，
 * 绝不跨越远程大模型调用（模型调用在 ReActAgentService 的 Flux 中完成）。
 *
 * <p><b>不伪造数据</b>：Token / 成本等无法真实获取的字段留空或置 0；
 * 失败的回复不会被写成成功的 assistant 消息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessagePersistenceService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final int MAX_SEQ_RETRY = 3;

    private final ChatMessageMapper chatMessageMapper;
    private final ConversationMapper conversationMapper;

    /**
     * 持久化用户消息。
     *
     * @param intent 本次意图；非空时同时维护 conversation.intent_primary
     * @return 已写入的消息（含 seqNo）
     */
    @Transactional
    public ChatMessage saveUserMessage(Conversation conversation, String content, String intent) {
        var saved = insertWithSeqRetry(conversation, ROLE_USER, content, 0, null, null, "USER", null);
        if (intent != null && !intent.isBlank()) {
            conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                    .eq(Conversation::getId, conversation.getId())
                    .set(Conversation::getIntentPrimary, intent.trim().toUpperCase()));
        }
        return saved;
    }

    /** 分类完成后只更新会话意图，不重复插入用户消息。 */
    @Transactional
    public void updateIntent(Conversation conversation, String intent) {
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversation.getId())
                .eq(Conversation::getTenantId, conversation.getTenantId())
                .set(Conversation::getIntentPrimary, intent));
        conversation.setIntentPrimary(intent);
    }

    /**
     * 持久化模型成功完成后的 assistant 消息。
     *
     * @param modelName  实际使用的模型名（可空）
     * @param latencyMs  从提问到完成的总耗时（可空）
     */
    @Transactional
    public ChatMessage saveAssistantMessage(Conversation conversation, String content,
                                           String modelName, Integer latencyMs) {
        return insertWithSeqRetry(conversation, ROLE_ASSISTANT, content, 1, modelName, latencyMs, "AI", null);
    }

    /** 锁内判断发送资格，避免关闭、接管与买家写入发生竞态。 */
    @Transactional
    public ChatMessage saveWidgetUserMessage(Conversation conversation, String content) {
        var locked = lockConversation(conversation);
        if (locked.getStatus() == null || !java.util.Set.of(1, 2, 3, 4).contains(locked.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "会话已关闭，请重新开始咨询");
        }
        conversation.setStatus(locked.getStatus());
        conversation.setHumanAgentId(locked.getHumanAgentId());
        return saveUserMessage(conversation, content, null);
    }

    @Transactional
    public ChatMessage saveHumanMessage(Conversation conversation, String content, Long actorId) {
        var locked = lockConversation(conversation);
        if (actorId == null || !actorId.equals(locked.getHumanAgentId()) || !Integer.valueOf(4).equals(locked.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "请由已接管会话的客服回复");
        }
        return insertWithSeqRetry(conversation, ROLE_ASSISTANT, content, 0, null, null, "HUMAN", actorId);
    }

    public WidgetDtos.WidgetMessagesResponse widgetMessages(Conversation conversation, int afterSeqNo) {
        if (afterSeqNo < 0) throw new BusinessException(ErrorCode.BAD_REQUEST, "消息序号不能为负数");
        var rows = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getTenantId, conversation.getTenantId())
                .eq(ChatMessage::getConversationId, conversation.getId())
                .eq(ChatMessage::getConversationUuid, conversation.getConversationUuid())
                .in(ChatMessage::getRole, java.util.List.of("user", "assistant"))
                .and(w -> w.isNull(ChatMessage::getSource).or().in(ChatMessage::getSource, java.util.List.of("USER", "AI", "HUMAN")))
                .isNull(ChatMessage::getToolName).isNull(ChatMessage::getToolCalls)
                .gt(ChatMessage::getSeqNo, afterSeqNo).orderByAsc(ChatMessage::getSeqNo).last("LIMIT 101"));
        return new WidgetDtos.WidgetMessagesResponse(rows.stream().limit(100)
                .map(m -> new WidgetDtos.WidgetMessage(m.getMessageUuid(), m.getSeqNo(), m.getRole(),
                        m.getContent(), m.getCreatedAt(), m.getSource())).toList(), rows.size() > 100, conversation.getStatus());
    }

    private Conversation lockConversation(Conversation conversation) {
        var locked = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversation.getId())
                .eq(Conversation::getTenantId, conversation.getTenantId()).last("FOR UPDATE"));
        if (locked == null || !java.util.Objects.equals(locked.getConversationUuid(), conversation.getConversationUuid())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return locked;
    }

    /**
     * 同一会话内按 seqNo 升序读取全部消息。
     */
    public java.util.List<ChatMessage> listMessages(String conversationUuid) {
        return chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationUuid, conversationUuid)
                .orderByAsc(ChatMessage::getSeqNo)
                .orderByAsc(ChatMessage::getCreatedAt));
    }

    /**
     * 会话当前最大的 seqNo（无消息时返回 null）。
     *
     * <p>供对话记忆做一致性校验：Redis 记录的最后同步 seqNo 与之相等才认为缓存不落后。
     */
    public Integer latestSeqNo(String conversationUuid) {
        var last = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationUuid, conversationUuid)
                .orderByDesc(ChatMessage::getSeqNo)
                .last("LIMIT 1"));
        return last == null ? null : last.getSeqNo();
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    private ChatMessage insertWithSeqRetry(Conversation conversation, String role, String content,
                                           int isStreamed, String modelName, Integer latencyMs, String source, Long senderId) {
        for (int attempt = 1; attempt <= MAX_SEQ_RETRY; attempt++) {
            // 1. 锁定会话行，串行化同一会话的序号分配（必须与插入在同一事务内）
            var locked = lockConversation(conversation);

            if ("AI".equals(source) && (locked.getHumanAgentId() != null
                    || Integer.valueOf(5).equals(locked.getStatus()) || Integer.valueOf(6).equals(locked.getStatus()))) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "会话已由人工接管或关闭，停止保存 AI 回复");
            }

            int nextSeq = nextSeqNo(conversation.getId());

            var now = LocalDateTime.now();
            var message = new ChatMessage();
            message.setMessageUuid(UUID.randomUUID().toString());
            message.setConversationUuid(conversation.getConversationUuid());
            message.setConversationId(conversation.getId());
            message.setTenantId(conversation.getTenantId());
            message.setRole(role);
            message.setSource(source);
            message.setSenderId(senderId);
            message.setSeqNo(nextSeq);
            message.setContent(content);
            message.setContentType("TEXT");
            message.setIsStreamed(isStreamed);
            message.setCreatedAt(now);
            // Token / 成本当前无法真实获取，统一置 0，不伪造
            if (ROLE_ASSISTANT.equals(role)) {
                message.setPromptTokens(0);
                message.setCompletionTokens(0);
                message.setTotalTokens(0);
                message.setCostUsd(BigDecimal.ZERO);
                message.setModelProvider("AI".equals(source) ? "deepseek" : null);
                message.setModelName(modelName);
                message.setLatencyMs(latencyMs);
            }

            try {
                chatMessageMapper.insert(message);
            } catch (DuplicateKeyException e) {
                log.warn("会话 {} seqNo={} 冲突，重试分配（第 {} 次）",
                        conversation.getConversationUuid(), nextSeq, attempt);
                continue;
            }

            // 2. 同步维护会话计数与状态（仅数据库操作）
            bumpConversation(conversation.getId(), locked);
            return message;
        }
        throw new IllegalStateException("会话序号分配失败：并发冲突过多，uuid=" + conversation.getConversationUuid());
    }

    /** 取当前会话已分配的最大 seqNo + 1；已有行锁保护，读取是安全的。 */
    private int nextSeqNo(Long conversationId) {
        var last = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByDesc(ChatMessage::getSeqNo)
                .last("LIMIT 1"));
        return last == null || last.getSeqNo() == null ? 1 : last.getSeqNo() + 1;
    }

    /**
     * 用 SQL 原子自增维护 messageCount / lastMessageAt，避免读改写丢失更新。
     */
    private void bumpConversation(Long conversationId, Conversation locked) {
        var now = LocalDateTime.now();
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .setSql("message_count = message_count + 1")
                .set(Conversation::getLastMessageAt, now)
                .set(Conversation::getUpdatedAt, now));
    }
}
