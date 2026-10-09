package com.omnimerchant.agent.memory;

import com.omnimerchant.agent.entity.ChatMessage;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.service.ChatMessagePersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆同步服务：把「MySQL 完整历史」与「Redis 短期上下文」协调起来。
 *
 * <p>职责边界（对应任务十一、十二、十三、十五）：
 * <ul>
 *   <li><b>MySQL 是权威来源</b>：完整历史、前端查询、Inbox 都读它。</li>
 *   <li><b>Redis 只是短期上下文</b>：加速模型读取最近 N 条，可缺失、可过期。</li>
 *   <li>缓存缺失 / 过期 / 落后 / Redis 不可用 → 一律从 MySQL 按 seqNo 重建。</li>
 *   <li>Redis 写入是<b>尽力而为</b>：失败只记录，不影响已成功落库的真实回复。</li>
 * </ul>
 *
 * <p>一致性方案：Redis 侧记录「最后同步的 seqNo」，读取时与 MySQL 的
 * {@code latestSeqNo} 比对；相等才认为缓存不落后，否则按 MySQL 最近 N 条重建。
 *
 * <p>本类不放在 {@code ReActAgentService} 里，避免主链充斥缓存编排细节。
 */
@Slf4j
@Service
public class ConversationMemoryService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final RedisChatMemory redisChatMemory;
    private final ChatMessagePersistenceService chatMessagePersistenceService;
    private final StringRedisTemplate redisTemplate;
    private final int lastN;

    public ConversationMemoryService(
            RedisChatMemory redisChatMemory,
            ChatMessagePersistenceService chatMessagePersistenceService,
            StringRedisTemplate redisTemplate,
            @Value("${app.chat.memory.last-n:12}") int lastN) {
        this.redisChatMemory = redisChatMemory;
        this.chatMessagePersistenceService = chatMessagePersistenceService;
        this.redisTemplate = redisTemplate;
        this.lastN = lastN > 0 ? lastN : 12;
    }

    /**
     * 取用于模型的历史上下文（最近 N 条 USER / ASSISTANT）。
     *
     * <p>优先 Redis；未命中 / 落后 / 不可用时从 MySQL 重建。
     * <b>包含当前这条用户消息</b>（ChatController 已在调用前落库），
     * 调用方不得再额外追加，否则模型会收到两条相同消息。
     *
     * @param conversation       已校验的会话（提供真实 tenantId）
     * @param currentUserMessage 本轮用户问题（仅在历史为空时作为兜底追加）
     */
    public List<Message> getRecentMessages(Conversation conversation, String currentUserMessage) {
        var tenantId = conversation.getTenantId();
        var uuid = conversation.getConversationUuid();

        Integer dbLatestSeq;
        try {
            dbLatestSeq = chatMessagePersistenceService.latestSeqNo(uuid);
        } catch (Exception e) {
            log.warn("读取 MySQL 最新序号失败，尝试使用 Redis 缓存：{}", e.getMessage());
            dbLatestSeq = null;
        }

        // 1) 尝试 Redis，并在能校验时确认缓存不落后
        try {
            var cached = redisChatMemory.getLast(tenantId, uuid, lastN);
            if (!cached.isEmpty()) {
                var syncedSeq = readSyncedSeq(tenantId, uuid);
                if (dbLatestSeq != null && syncedSeq != null && syncedSeq.equals(dbLatestSeq)) {
                    return cached;
                }
                if (dbLatestSeq == null) {
                    // MySQL 不可用但缓存有数据：降级使用缓存（不假装已从库恢复）
                    log.warn("MySQL 不可用，降级使用 Redis 缓存历史：conv={}", uuid);
                    return cached;
                }
                log.info("Redis 记忆落后（syncedSeq={}, dbLatestSeq={}），将从 MySQL 重建：conv={}",
                        syncedSeq, dbLatestSeq, uuid);
            }
        } catch (Exception e) {
            log.warn("读取 Redis 记忆失败，降级到 MySQL：{}", e.getMessage());
        }

        // 2) 从 MySQL 重建
        if (dbLatestSeq == null) {
            // Redis 与 MySQL 都拿不到：不假装历史已恢复
            if (currentUserMessage != null && !currentUserMessage.isBlank()) {
                log.warn("无法读取会话历史，仅使用本轮用户消息继续：conv={}", uuid);
                return List.of(new UserMessage(currentUserMessage));
            }
            throw new IllegalStateException("无法读取会话历史：Redis 与 MySQL 均不可用");
        }

        var rebuilt = rebuildFromMysql(conversation);
        if (rebuilt.isEmpty()) {
            // 记录里没有任何有效历史（例如只有 system/tool），至少保留本轮问题
            if (currentUserMessage != null && !currentUserMessage.isBlank()) {
                return List.of(new UserMessage(currentUserMessage));
            }
        }
        return rebuilt;
    }

    /** 从 MySQL 取最近 N 条 USER / ASSISTANT，转为 Spring AI Message 并回写 Redis。 */
    private List<Message> rebuildFromMysql(Conversation conversation) {
        var all = chatMessagePersistenceService.listMessages(conversation.getConversationUuid());

        var usable = new ArrayList<ChatMessage>();
        for (var message : all) {
            if (message.getRole() == null || message.getContent() == null
                    || message.getContent().isBlank()) {
                continue;
            }
            if (ROLE_USER.equals(message.getRole()) || ROLE_ASSISTANT.equals(message.getRole())) {
                usable.add(message);
            }
        }
        var start = Math.max(0, usable.size() - lastN);
        var recent = usable.subList(start, usable.size());

        var messages = new ArrayList<Message>(recent.size());
        for (var message : recent) {
            messages.add(toSpringMessage(message));
        }

        // 回写 Redis（尽力而为，失败不影响本次返回）
        syncFullHistory(conversation, recent);
        return messages;
    }

    /**
     * 用 MySQL 的真实最近 N 条重建 Redis 缓存（覆盖旧内容，避免残留）。
     */
    private void syncFullHistory(Conversation conversation, List<ChatMessage> recent) {
        var tenantId = conversation.getTenantId();
        var uuid = conversation.getConversationUuid();
        try {
            redisChatMemory.clear(tenantId, uuid);
            if (!recent.isEmpty()) {
                redisChatMemory.add(tenantId, uuid,
                        recent.stream().map(this::toSpringMessage).toList());
                markSynced(tenantId, uuid, recent.get(recent.size() - 1).getSeqNo());
            }
        } catch (Exception e) {
            log.warn("回写 Redis 记忆失败（MySQL 历史仍完整）：conv={}, error={}", uuid, e.getMessage());
        }
    }

    /**
     * 用户消息落库后同步 Redis（尽力而为）。
     */
    public void syncUserMessage(Conversation conversation, ChatMessage saved) {
        syncMessage(conversation, saved, ROLE_USER);
    }

    /**
     * 助手消息成功落库后同步 Redis（尽力而为）。
     *
     * <p>只有 MySQL 已保存成功才会调用本方法，因此不会把失败回复写进记忆。
     */
    public void syncAssistantMessage(Conversation conversation, ChatMessage saved) {
        syncMessage(conversation, saved, ROLE_ASSISTANT);
    }

    private void syncMessage(Conversation conversation, ChatMessage saved, String expectedRole) {
        if (saved == null || saved.getContent() == null || saved.getContent().isBlank()) {
            return;
        }
        if (!expectedRole.equals(saved.getRole())) {
            log.warn("同步记忆时角色不匹配，跳过：期望={}, 实际={}", expectedRole, saved.getRole());
            return;
        }
        var targetSeq = saved.getSeqNo();
        if (targetSeq == null) {
            return;
        }
        var tenantId = conversation.getTenantId();
        var uuid = conversation.getConversationUuid();
        try {
            var syncedSeq = readSyncedSeq(tenantId, uuid);
            // 关键：增量同步要求缓存恰好停在目标消息的前一条。
            // 否则（缓存为空 / 被清空 / 落后）不写入，避免"半截缓存"被误判为完整，
            // 交给 getRecentMessages 按 MySQL 真实历史整体重建。
            if (syncedSeq == null || syncedSeq != targetSeq - 1) {
                log.debug("缓存未就绪或落后，跳过增量同步，等待按 MySQL 重建：conv={}, synced={}, target={}",
                        uuid, syncedSeq, targetSeq);
                return;
            }
            redisChatMemory.add(tenantId, uuid, List.of(toSpringMessage(saved)));
            markSynced(tenantId, uuid, targetSeq);
        } catch (Exception e) {
            // Redis 故障不能影响已经成功落库的真实回复；下轮从 MySQL 恢复
            log.warn("同步 Redis 记忆失败（MySQL 已成功，将在下轮重建）：conv={}, error={}",
                    uuid, e.getMessage());
        }
    }

    /** 清理某会话记忆（供测试/运维使用）。 */
    public void clear(Conversation conversation) {
        var tenantId = conversation.getTenantId();
        var uuid = conversation.getConversationUuid();
        redisChatMemory.clear(tenantId, uuid);
        redisTemplate.delete(syncMarkerKey(tenantId, uuid));
    }

    private Message toSpringMessage(ChatMessage message) {
        if (ROLE_USER.equals(message.getRole())) {
            return new UserMessage(message.getContent());
        }
        return new AssistantMessage(message.getContent());
    }

    // ------------------------------------------------------------------
    // 「最后同步 seqNo」标记，用于检测缓存是否落后
    // ------------------------------------------------------------------

    private void markSynced(Long tenantId, String uuid, Integer seqNo) {
        if (seqNo == null) {
            return;
        }
        redisTemplate.opsForValue().set(syncMarkerKey(tenantId, uuid), String.valueOf(seqNo));
    }

    private Integer readSyncedSeq(Long tenantId, String uuid) {
        var value = redisTemplate.opsForValue().get(syncMarkerKey(tenantId, uuid));
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String syncMarkerKey(Long tenantId, String uuid) {
        var base = com.omnimerchant.common.constant.Constants.REDIS_PREFIX
                + String.format(com.omnimerchant.common.constant.Constants.CONV_CTX_KEY, tenantId, uuid);
        return base + ":seq";
    }
}
