package com.omnimerchant.agent.memory;

import com.omnimerchant.agent.entity.ChatMessage;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.service.ChatMessagePersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConversationMemoryService 单元测试：缓存未命中/落后/故障时从 MySQL 恢复，
 * 并保证当前用户消息只出现一次。
 */
class ConversationMemoryServiceTest {

    private RedisChatMemory redisChatMemory;
    private ChatMessagePersistenceService persistence;
    private StringRedisTemplate redisTemplate;
    private ConversationMemoryService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisChatMemory = mock(RedisChatMemory.class);
        persistence = mock(ChatMessagePersistenceService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        var valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        service = new ConversationMemoryService(redisChatMemory, persistence, redisTemplate, 12);
    }

    private Conversation conversation(Long tenantId, String uuid) {
        var c = new Conversation();
        c.setId(1L);
        c.setTenantId(tenantId);
        c.setConversationUuid(uuid);
        return c;
    }

    private ChatMessage message(String role, String content, int seq) {
        var m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        m.setSeqNo(seq);
        return m;
    }

    @Test
    void shouldRestoreFromMysqlWhenRedisMisses() {
        var conv = conversation(1001L, "conv-1");
        when(redisChatMemory.getLast(1001L, "conv-1", 12)).thenReturn(List.of());
        when(persistence.latestSeqNo("conv-1")).thenReturn(3);
        when(persistence.listMessages("conv-1")).thenReturn(List.of(
                message("user", "我的订单 #1001 发货了吗？", 1),
                message("assistant", "已发货。", 2),
                message("user", "那它什么时候到？", 3)));

        var history = service.getRecentMessages(conv, "那它什么时候到？");

        assertThat(history).extracting(Message::getText)
                .containsExactly("我的订单 #1001 发货了吗？", "已发货。", "那它什么时候到？");
        assertThat(history.get(0)).isInstanceOf(UserMessage.class);
        assertThat(history.get(1)).isInstanceOf(AssistantMessage.class);
        // 回写 Redis
        verify(redisChatMemory).clear(1001L, "conv-1");
        verify(redisChatMemory).add(anyLong(), anyString(), any(List.class));
    }

    @Test
    void currentUserMessageShouldNotBeDuplicated() {
        var conv = conversation(1001L, "conv-1");
        when(redisChatMemory.getLast(1001L, "conv-1", 12)).thenReturn(List.of());
        when(persistence.latestSeqNo("conv-1")).thenReturn(1);
        when(persistence.listMessages("conv-1")).thenReturn(List.of(
                message("user", "我的订单 #1001 发货了吗？", 1)));

        var history = service.getRecentMessages(conv, "我的订单 #1001 发货了吗？");

        // 当前消息已在 MySQL 中，历史里只应出现一次
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getText()).isEqualTo("我的订单 #1001 发货了吗？");
    }

    @Test
    void redisFailureShouldFallBackToMysql() {
        var conv = conversation(1001L, "conv-1");
        when(redisChatMemory.getLast(1001L, "conv-1", 12))
                .thenThrow(new RuntimeException("Redis 连接失败"));
        when(persistence.latestSeqNo("conv-1")).thenReturn(1);
        when(persistence.listMessages("conv-1")).thenReturn(List.of(
                message("user", "历史消息", 1)));

        var history = service.getRecentMessages(conv, "历史消息");

        // 不能把缓存失败误判为历史为空
        assertThat(history).extracting(Message::getText).containsExactly("历史消息");
    }

    @Test
    void staleCacheShouldBeRebuiltFromMysql() {
        var conv = conversation(1001L, "conv-1");
        // Redis 有缓存但同步标记落后于数据库
        when(redisChatMemory.getLast(1001L, "conv-1", 12))
                .thenReturn(List.of(new UserMessage("旧缓存")));
        when(redisTemplate.opsForValue().get(anyString())).thenReturn("1");
        when(persistence.latestSeqNo("conv-1")).thenReturn(2);
        when(persistence.listMessages("conv-1")).thenReturn(List.of(
                message("user", "旧缓存", 1),
                message("assistant", "新回复", 2)));

        var history = service.getRecentMessages(conv, "新问题");

        assertThat(history).extracting(Message::getText).containsExactly("旧缓存", "新回复");
    }

    @Test
    void shouldLimitToLastN() {
        var conv = conversation(1001L, "conv-1");
        when(redisChatMemory.getLast(1001L, "conv-1", 12)).thenReturn(List.of());
        when(persistence.latestSeqNo("conv-1")).thenReturn(20);
        var all = new ArrayList<ChatMessage>();
        for (int i = 1; i <= 20; i++) {
            all.add(message(i % 2 == 0 ? "assistant" : "user", "消息" + i, i));
        }
        when(persistence.listMessages("conv-1")).thenReturn(all);

        var history = service.getRecentMessages(conv, "消息20");

        assertThat(history).hasSize(12);
        assertThat(history.get(0).getText()).isEqualTo("消息9");
    }

    @Test
    void whenBothRedisAndMysqlUnavailableShouldFailExplicitly() {
        var conv = conversation(1001L, "conv-1");
        when(persistence.latestSeqNo("conv-1")).thenThrow(new RuntimeException("MySQL 不可用"));
        when(redisChatMemory.getLast(1001L, "conv-1", 12))
                .thenThrow(new RuntimeException("Redis 不可用"));

        // 当前消息为空的极端情况下必须明确失败，而不是假装历史已恢复
        assertThatThrownBy(() -> service.getRecentMessages(conv, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法读取会话历史");
    }

    @Test
    void systemAndToolRolesShouldBeExcludedFromHistory() {
        var conv = conversation(1001L, "conv-1");
        when(redisChatMemory.getLast(1001L, "conv-1", 12)).thenReturn(List.of());
        when(persistence.latestSeqNo("conv-1")).thenReturn(3);
        when(persistence.listMessages("conv-1")).thenReturn(List.of(
                message("system", "系统提示", 1),
                message("user", "用户问题", 2),
                message("tool", "工具结果", 3)));

        var history = service.getRecentMessages(conv, "用户问题");

        assertThat(history).extracting(Message::getText).containsExactly("用户问题");
    }

    @Test
    void syncAssistantShouldNotWriteWhenRoleMismatch() {
        var conv = conversation(1001L, "conv-1");
        var wrongRole = message("user", "内容", 1);

        service.syncAssistantMessage(conv, wrongRole);

        verify(redisChatMemory, never()).add(anyLong(), anyString(), any(List.class));
    }

    @Test
    void incrementalSyncShouldSkipWhenCacheNotAligned() {
        var conv = conversation(1001L, "conv-1");
        // 缓存没有 seq 标记（syncedSeq=null）→ 不应盲目追加
        when(redisTemplate.opsForValue().get(anyString())).thenReturn(null);

        service.syncAssistantMessage(conv, message("assistant", "回答", 6));

        verify(redisChatMemory, never()).add(anyLong(), anyString(), any(List.class));
    }

    @Test
    void incrementalSyncShouldAppendWhenAligned() {
        var conv = conversation(1001L, "conv-1");
        // 缓存停在 seq=1，目标消息 seq=2 → 正好衔接，可以追加
        when(redisTemplate.opsForValue().get(anyString())).thenReturn("1");

        service.syncAssistantMessage(conv, message("assistant", "回答", 2));

        verify(redisChatMemory).add(anyLong(), anyString(), any(List.class));
    }

    @Test
    void syncShouldSwallowRedisFailure() {
        var conv = conversation(1001L, "conv-1");
        // 对齐到 seq=1，目标 seq=2，使其进入写入分支
        when(redisTemplate.opsForValue().get(anyString())).thenReturn("1");
        org.mockito.Mockito.doThrow(new RuntimeException("Redis 挂了"))
                .when(redisChatMemory).add(anyLong(), anyString(), any(List.class));

        // Redis 故障不能抛出，必须被吞掉（MySQL 已成功）
        service.syncAssistantMessage(conv, message("assistant", "回答", 2));
    }

    @Test
    void shouldNotCallMysqlListWhenCacheFresh() {
        var conv = conversation(1001L, "conv-1");
        when(persistence.latestSeqNo("conv-1")).thenReturn(2);
        when(redisChatMemory.getLast(1001L, "conv-1", 12))
                .thenReturn(List.of(new UserMessage("问"), new AssistantMessage("答")));
        when(redisTemplate.opsForValue().get(anyString())).thenReturn("2");

        var history = service.getRecentMessages(conv, "问");

        assertThat(history).hasSize(2);
        verify(persistence, never()).listMessages(anyString());
    }
}
