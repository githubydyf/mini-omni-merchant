package com.omnimerchant.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.tenant.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisChatMemory 单元测试（用内存 Map 模拟 Redis List/Value，不依赖真实 Redis）。
 *
 * <p>覆盖任务 §27 要求的 12 项：add / get / getLast / 顺序 / 类型恢复 / clear /
 * TTL / 租户隔离 / 缺租户不写共享 Key / 空列表 / 非法 JSON / 列表长度上限。
 *
 * <p>真实 Redis 联调另见集成验证步骤。
 */
class RedisChatMemoryTest {

    /** 以 key → list 模拟 Redis List。 */
    private final ConcurrentHashMap<String, List<String>> lists = new ConcurrentHashMap<>();
    /** 记录设置过 TTL 的 key。 */
    private final Set<String> expiring = new HashSet<>();

    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOps;
    private RedisChatMemory memory;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        var valueOps = mock(ValueOperations.class);

        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        when(listOps.rightPush(anyString(), anyString()))
                .thenAnswer((Answer<Long>) inv -> {
                    var key = inv.getArgument(0, String.class);
                    var value = inv.getArgument(1, String.class);
                    var list = lists.computeIfAbsent(key, k -> new ArrayList<>());
                    list.add(value);
                    return (long) list.size();
                });
        when(listOps.size(anyString()))
                .thenAnswer((Answer<Long>) inv -> (long) lists.getOrDefault(
                        inv.getArgument(0, String.class), List.of()).size());
        when(listOps.range(anyString(), anyLong(), anyLong()))
                .thenAnswer((Answer<List<String>>) inv -> {
                    var key = inv.getArgument(0, String.class);
                    var list = lists.getOrDefault(key, List.of());
                    if (list.isEmpty()) {
                        return List.of();
                    }
                    long start = inv.getArgument(1, Long.class);
                    long end = inv.getArgument(2, Long.class);
                    int from = (int) Math.max(0, start);
                    int to = (int) Math.min(list.size() - 1, end);
                    if (from > to) {
                        return List.of();
                    }
                    return new ArrayList<>(list.subList(from, to + 1));
                });
        org.mockito.Mockito.doAnswer(inv -> {
            var key = inv.getArgument(0, String.class);
            long start = inv.getArgument(1, Long.class);
            long end = inv.getArgument(2, Long.class);
            var list = lists.computeIfAbsent(key, k -> new ArrayList<>());
            int size = list.size();
            int from = (int) Math.max(0, start < 0 ? size + start : start);
            int to = (int) Math.min(size - 1, end < 0 ? size + end : end);
            var trimmed = from > to ? List.<String>of() : new ArrayList<>(list.subList(from, to + 1));
            lists.put(key, new ArrayList<>(trimmed));
            return null;
        }).when(listOps).trim(anyString(), anyLong(), anyLong());
        when(redisTemplate.expire(anyString(), any(Duration.class)))
                .thenAnswer((Answer<Boolean>) inv -> {
                    expiring.add(inv.getArgument(0, String.class));
                    return true;
                });
        when(redisTemplate.delete(anyString()))
                .thenAnswer((Answer<Boolean>) inv -> {
                    lists.remove(inv.getArgument(0, String.class));
                    expiring.remove(inv.getArgument(0, String.class));
                    return true;
                });
        when(redisTemplate.hasKey(anyString()))
                .thenAnswer((Answer<Boolean>) inv ->
                        lists.containsKey(inv.getArgument(0, String.class)));
        when(redisTemplate.getExpire(anyString()))
                .thenAnswer((Answer<Long>) inv -> {
                    var key = inv.getArgument(0, String.class);
                    return expiring.contains(key) ? 604800L : -2L;
                });
        when(valueOps.get(anyString())).thenReturn(null);

        memory = new RedisChatMemory(redisTemplate, new ObjectMapper(), 12, 50, 7);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private String key(Long tenantId, String conv) {
        return "omni:conv:ctx:" + tenantId + ":" + conv;
    }

    @Test
    void addShouldStoreMessages() {
        memory.add(1001L, "conv-a", List.of(new UserMessage("你好"), new AssistantMessage("您好")));
        assertThat(lists.get(key(1001L, "conv-a"))).hasSize(2);
    }

    @Test
    void getShouldReturnStoredMessages() {
        memory.add(1001L, "conv-a", List.of(new UserMessage("你好")));
        assertThat(memory.getLast(1001L, "conv-a")).hasSize(1);
    }

    @Test
    void getLastShouldReturnOnlyRecentN() {
        var messages = new ArrayList<Message>();
        for (int i = 1; i <= 10; i++) {
            messages.add(new UserMessage("消息" + i));
        }
        memory.add(1001L, "conv-a", messages);

        var last3 = memory.getLast(1001L, "conv-a", 3);
        assertThat(last3).hasSize(3);
        assertThat(last3).extracting(Message::getText)
                .containsExactly("消息8", "消息9", "消息10");
    }

    @Test
    void messageOrderShouldBeOldestFirst() {
        memory.add(1001L, "conv-a", List.of(
                new UserMessage("第1条"), new AssistantMessage("第2条"), new UserMessage("第3条")));
        assertThat(memory.getLast(1001L, "conv-a")).extracting(Message::getText)
                .containsExactly("第1条", "第2条", "第3条");
    }

    @Test
    void userAndAssistantTypesShouldBeRestored() {
        memory.add(1001L, "conv-a", List.of(new UserMessage("问"), new AssistantMessage("答")));
        var restored = memory.getLast(1001L, "conv-a");
        assertThat(restored.get(0)).isInstanceOf(UserMessage.class);
        assertThat(restored.get(1)).isInstanceOf(AssistantMessage.class);
    }

    @Test
    void clearShouldRemoveConversationCache() {
        memory.add(1001L, "conv-a", List.of(new UserMessage("你好")));
        memory.clear(1001L, "conv-a");
        assertThat(memory.getLast(1001L, "conv-a")).isEmpty();
        assertThat(lists).doesNotContainKey(key(1001L, "conv-a"));
    }

    @Test
    void ttlShouldBeSetOnAdd() {
        memory.add(1001L, "conv-a", List.of(new UserMessage("你好")));
        assertThat(expiring).contains(key(1001L, "conv-a"));
        verify(redisTemplate).expire(eq(key(1001L, "conv-a")), eq(Duration.ofDays(7)));
    }

    @Test
    void tenantsShouldBeIsolatedForSameConversationId() {
        memory.add(1001L, "conv-a", List.of(new UserMessage("租户1001的消息")));
        memory.add(1002L, "conv-a", List.of(new UserMessage("租户1002的消息")));

        assertThat(key(1001L, "conv-a")).isNotEqualTo(key(1002L, "conv-a"));
        assertThat(memory.getLast(1001L, "conv-a")).extracting(Message::getText)
                .containsExactly("租户1001的消息");
        assertThat(memory.getLast(1002L, "conv-a")).extracting(Message::getText)
                .containsExactly("租户1002的消息");
    }

    @Test
    void conversationsInSameTenantShouldBeIsolated() {
        memory.add(1001L, "conv-a", List.of(new UserMessage("会话A")));
        memory.add(1001L, "conv-b", List.of(new UserMessage("会话B")));
        assertThat(memory.getLast(1001L, "conv-a")).extracting(Message::getText).containsExactly("会话A");
        assertThat(memory.getLast(1001L, "conv-b")).extracting(Message::getText).containsExactly("会话B");
    }

    @Test
    void missingTenantShouldNotWriteSharedKey() {
        // 没有设置 TenantContextHolder，标准接口必须拒绝，而不是写到 omni:conv:ctx:0:xxx
        assertThatThrownBy(() -> memory.add("conv-a", List.of(new UserMessage("你好"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租户");
        assertThat(lists).isEmpty();
        assertThat(lists).doesNotContainKey("omni:conv:ctx:0:conv-a");
    }

    @Test
    void standardInterfaceShouldUseTenantContextWhenPresent() {
        TenantContextHolder.set(1001L);
        memory.add("conv-a", List.of(new UserMessage("上下文租户")));
        assertThat(lists).containsKey(key(1001L, "conv-a"));
    }

    @Test
    void emptyListShouldReturnEmptyHistory() {
        assertThat(memory.getLast(1001L, "not-exist")).isEmpty();
    }

    @Test
    void corruptedJsonShouldBeSkipped() {
        var k = key(1001L, "conv-a");
        lists.put(k, new ArrayList<>(List.of(
                "{不是合法JSON", "{\"type\":\"USER\",\"text\":\"正常消息\"}")));
        var restored = memory.getLast(1001L, "conv-a");
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).getText()).isEqualTo("正常消息");
    }

    @Test
    void unsupportedTypeShouldBeSkippedOnWriteAndRead() {
        // 写入：SystemMessage 不应被序列化
        var written = memory.add(1001L, "conv-a", List.of(
                new SystemMessage("系统提示"), new UserMessage("用户消息")));
        assertThat(written).isEqualTo(1);

        // 读取：万一缓存里混入未知类型，也必须跳过，不能变成 ASSISTANT
        var k = key(1001L, "conv-a");
        lists.get(k).add("{\"type\":\"TOOL\",\"text\":\"工具结果\"}");
        lists.get(k).add("{\"type\":\"UNKNOWN\",\"text\":\"未知\"}");
        assertThat(memory.getLast(1001L, "conv-a")).extracting(Message::getText)
                .containsExactly("用户消息");
    }

    @Test
    void listShouldNotGrowBeyondMaxMessages() {
        for (int i = 1; i <= 60; i++) {
            memory.add(1001L, "conv-a", List.of(new UserMessage("第" + i + "条")));
        }
        assertThat(lists.get(key(1001L, "conv-a"))).hasSize(50);
        assertThat(memory.getLast(1001L, "conv-a", 50).get(0).getText()).isEqualTo("第11条");
    }
}
