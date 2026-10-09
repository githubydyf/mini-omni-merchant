package com.omnimerchant.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.common.constant.Constants;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 基于 Redis 的短期对话记忆（Spring AI {@code ChatMemory} 实现）。
 *
 * <p>复现自参考项目 {@code memory/RedisChatMemory}，但针对当前阶段做了两处必要调整：
 *
 * <p><b>一、租户隔离必须真实可信</b>
 * <pre>
 * Redis Key: omni:conv:ctx:{tenantId}:{conversationUuid}
 * </pre>
 * 参考项目在 tenantId 缺失时用 {@code 0L} 兜底，这会让不同租户落到同一个共享 Key。
 * 本实现<b>拒绝</b>这种做法：当租户上下文缺失时直接抛 {@link IllegalStateException}，
 * 宁可失败也不产生无隔离的共享缓存。同时提供显式接收 tenantId 的方法，
 * 供 {@code ReActAgentService.chatEvents()} 用已验证的 Conversation 传入真实租户，
 * 避免依赖 Reactor 线程上的 ThreadLocal。
 *
 * <p><b>二、列表长度受控</b>
 * 每次写入后用 {@code LTRIM} 保留最近 {@code maxMessages} 条，防止 Redis 列表无限增长。
 *
 * <p>存储格式为 JSON：{@code {"type":"USER","text":"..."}}，仅支持 USER / ASSISTANT；
 * 其他类型按明确规则<b>跳过</b>并告警，绝不静默转换为 ASSISTANT。
 */
@Slf4j
@Component
public class RedisChatMemory implements ChatMemory {

    /** 参考项目默认值；当前由配置覆盖。 */
    private static final int DEFAULT_LAST_N = 12;
    private static final int DEFAULT_MAX_MESSAGES = 50;
    private static final int DEFAULT_TTL_DAYS = 7;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int lastN;
    private final int maxMessages;
    private final int ttlDays;

    public RedisChatMemory(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${app.chat.memory.last-n:" + DEFAULT_LAST_N + "}") int lastN,
            @Value("${app.chat.memory.max-messages:" + DEFAULT_MAX_MESSAGES + "}") int maxMessages,
            @Value("${app.chat.memory.ttl-days:" + DEFAULT_TTL_DAYS + "}") int ttlDays) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.lastN = lastN > 0 ? lastN : DEFAULT_LAST_N;
        this.maxMessages = maxMessages > 0 ? maxMessages : DEFAULT_MAX_MESSAGES;
        this.ttlDays = ttlDays > 0 ? ttlDays : DEFAULT_TTL_DAYS;
    }

    // ==================================================================
    // ChatMemory 标准接口：租户从 TenantContextHolder 取，缺失即拒绝
    // ==================================================================

    @Override
    public void add(String conversationId, List<Message> messages) {
        add(requireTenantId(), conversationId, messages);
    }

    @Override
    public List<Message> get(String conversationId) {
        return getLast(requireTenantId(), conversationId, lastN);
    }

    @Override
    public void clear(String conversationId) {
        clear(requireTenantId(), conversationId);
    }

    // ==================================================================
    // 显式 tenantId 版本：供 chatEvents 用已验证的 Conversation 调用
    // ==================================================================

    /**
     * 追加消息（先发先写，使用 rightPush 保持时间顺序）。
     *
     * @return 实际写入的消息条数（不支持的类型会被跳过）
     */
    public int add(Long tenantId, String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        var key = buildKey(tenantId, conversationId);
        int written = 0;
        for (var message : messages) {
            var json = serializeMessage(conversationId, message);
            if (json == null) {
                // 不支持的类型：跳过，不写入，避免污染历史角色
                continue;
            }
            redisTemplate.opsForList().rightPush(key, json);
            written++;
        }
        if (written == 0) {
            return 0;
        }
        // 控制列表长度：只保留最近 maxMessages 条
        redisTemplate.opsForList().trim(key, -maxMessages, -1);
        // 每次成功更新后刷新 TTL
        redisTemplate.expire(key, Duration.ofDays(ttlDays));
        return written;
    }

    /** 追加单条消息。 */
    public int add(Long tenantId, String conversationId, Message message) {
        return add(tenantId, conversationId, List.of(message));
    }

    /** 读取最近 lastN 条（默认配置值）。 */
    public List<Message> getLast(Long tenantId, String conversationId) {
        return getLast(tenantId, conversationId, lastN);
    }

    /**
     * 读取最近 lastN 条，返回顺序为「先发生的在前」。
     */
    public List<Message> getLast(Long tenantId, String conversationId, int n) {
        if (n <= 0) {
            return List.of();
        }
        var key = buildKey(tenantId, conversationId);
        var size = redisTemplate.opsForList().size(key);
        if (size == null || size == 0) {
            return List.of();
        }
        var start = Math.max(0, size - n);
        var jsons = redisTemplate.opsForList().range(key, start, size - 1);
        if (jsons == null || jsons.isEmpty()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>();
        for (var json : jsons) {
            var message = deserializeMessage(conversationId, json);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    /** 删除指定会话的记忆。 */
    public void clear(Long tenantId, String conversationId) {
        redisTemplate.delete(buildKey(tenantId, conversationId));
        log.debug("已清理会话记忆：tenant={}, conv={}", tenantId, conversationId);
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    /** 从线程上下文取租户；缺失时明确拒绝，绝不使用共享兜底 Key。 */
    private Long requireTenantId() {
        var tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "缺少租户上下文，拒绝访问无隔离的会话记忆缓存；请使用显式 tenantId 的方法");
        }
        return tenantId;
    }

    /** Key：omni:conv:ctx:{tenantId}:{conversationUuid}，不同租户互相隔离。 */
    private String buildKey(Long tenantId, String conversationId) {
        if (tenantId == null) {
            throw new IllegalStateException("tenantId 不能为空，拒绝构造共享记忆 Key");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        return Constants.REDIS_PREFIX
                + String.format(Constants.CONV_CTX_KEY, tenantId, conversationId);
    }

    /** 序列化：只支持 USER / ASSISTANT，其他类型返回 null 由调用方跳过。 */
    private String serializeMessage(String conversationId, Message message) {
        if (message == null || message.getMessageType() == null) {
            return null;
        }
        var type = message.getMessageType();
        if (type != MessageType.USER && type != MessageType.ASSISTANT) {
            log.warn("会话 {} 的记忆跳过不支持的消息类型：{}", conversationId, type);
            return null;
        }
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "type", type.name(),
                    "text", message.getText() == null ? "" : message.getText()));
        } catch (JsonProcessingException e) {
            log.error("会话 {} 的消息序列化失败：{}", conversationId, e.getMessage());
            return null;
        }
    }

    /** 反序列化：非法 JSON 或未知类型都按明确规则跳过（返回 null），不伪造为 ASSISTANT。 */
    private Message deserializeMessage(String conversationId, String json) {
        try {
            var wrapper = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
            var rawType = wrapper.get("type");
            var text = wrapper.get("text") == null ? "" : wrapper.get("text");
            if (rawType == null) {
                log.warn("会话 {} 的记忆缺少 type 字段，跳过该条", conversationId);
                return null;
            }
            MessageType type;
            try {
                type = MessageType.valueOf(rawType);
            } catch (IllegalArgumentException e) {
                log.warn("会话 {} 的记忆包含未知类型 {}，跳过该条", conversationId, rawType);
                return null;
            }
            return switch (type) {
                case USER -> new UserMessage(text);
                case ASSISTANT -> new AssistantMessage(text);
                default -> {
                    log.warn("会话 {} 的记忆包含不支持的类型 {}，跳过该条", conversationId, type);
                    yield null;
                }
            };
        } catch (JsonProcessingException e) {
            log.warn("会话 {} 的记忆 JSON 解析失败，跳过该条：{}", conversationId, e.getMessage());
            return null;
        }
    }

    /** 供测试与调试：只读检查 Key 是否存在。 */
    public boolean exists(Long tenantId, String conversationId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(tenantId, conversationId)));
    }

    /** 供测试与调试：读取剩余 TTL（秒）。 */
    public Long ttlSeconds(Long tenantId, String conversationId) {
        return redisTemplate.getExpire(buildKey(tenantId, conversationId));
    }

    /** 当前生效的默认读取条数（对齐原项目「最近 12 条」）。 */
    public int defaultLastN() {
        return lastN;
    }

    /** 当前生效的列表长度上限。 */
    public int maxMessages() {
        return maxMessages;
    }

    /** 空历史常量，避免调用方拿到 null。 */
    public static List<Message> emptyHistory() {
        return Collections.emptyList();
    }
}
