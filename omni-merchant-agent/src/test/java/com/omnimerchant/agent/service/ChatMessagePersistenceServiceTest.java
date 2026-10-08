package com.omnimerchant.agent.service;

import com.omnimerchant.agent.entity.ChatMessage;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.mapper.ChatMessageMapper;
import com.omnimerchant.agent.mapper.ConversationMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息持久化测试：
 * <ul>
 *   <li>seqNo 从 1 开始，空会话为 1；</li>
 *   <li>已有消息时自增至 max+1；</li>
 *   <li>唯一键冲突（并发）时重试并最终成功；</li>
 *   <li>保存后同步自增 conversation 计数与 lastMessageAt。</li>
 * </ul>
 */
class ChatMessagePersistenceServiceTest {

    private final ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
    private final ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private final ChatMessagePersistenceService service =
            new ChatMessagePersistenceService(messageMapper, conversationMapper);

    /**
     * LambdaQueryWrapper 依赖 MyBatis-Plus 的实体 lambda 缓存，
     * 单元测试（无 Spring/MyBatis 容器）需要手动初始化实体元信息。
     */
    @BeforeAll
    static void initTableInfo() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Conversation.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ChatMessage.class);
    }

    private Conversation conversation() {
        var c = new Conversation();
        c.setId(10L);
        c.setConversationUuid("conv-1");
        c.setTenantId(1001L);
        c.setMessageCount(0);
        return c;
    }

    @Test
    void firstMessageShouldUseSeqOne() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation());
        when(messageMapper.selectOne(any())).thenReturn(null);

        var saved = service.saveUserMessage(conversation(), "你好", "POLICY_QA");

        assertThat(saved.getSeqNo()).isEqualTo(1);
        assertThat(saved.getRole()).isEqualTo("user");
        assertThat(saved.getMessageUuid()).isNotBlank();
        assertThat(saved.getTenantId()).isEqualTo(1001L);
        assertThat(saved.getConversationId()).isEqualTo(10L);
    }

    @Test
    void subsequentMessageShouldIncrementSeq() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation());
        var last = new ChatMessage();
        last.setSeqNo(7);
        when(messageMapper.selectOne(any())).thenReturn(last);

        var saved = service.saveAssistantMessage(conversation(), "回答", "deepseek-flash", 120);

        assertThat(saved.getSeqNo()).isEqualTo(8);
        assertThat(saved.getRole()).isEqualTo("assistant");
        assertThat(saved.getIsStreamed()).isEqualTo(1);
        assertThat(saved.getModelName()).isEqualTo("deepseek-flash");
        assertThat(saved.getLatencyMs()).isEqualTo(120);
        assertThat(saved.getTotalTokens()).isZero();
    }

    @Test
    void duplicateSeqShouldRetryAndSucceed() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation());
        when(messageMapper.selectOne(any())).thenReturn(null);
        // 第一次插入因并发冲突失败，第二次成功
        when(messageMapper.insert(any(ChatMessage.class)))
                .thenThrow(new DuplicateKeyException("uk_conv_seq"))
                .thenReturn(1);

        var saved = service.saveUserMessage(conversation(), "重试消息", "ORDER_STATUS");

        assertThat(saved.getSeqNo()).isEqualTo(1);
        verify(messageMapper, times(2)).insert(any(ChatMessage.class));
    }

    @Test
    void shouldBumpConversationCounterAfterInsert() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation());
        when(messageMapper.selectOne(any())).thenReturn(null);

        service.saveUserMessage(conversation(), "你好", "POLICY_QA");

        var updateCaptor = ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(conversationMapper, org.mockito.Mockito.atLeastOnce())
                .update(any(), updateCaptor.capture());
        var allSqlSet = String.valueOf(updateCaptor.getAllValues().stream()
                .map(w -> String.valueOf(w.getSqlSet()))
                .toList());
        assertThat(allSqlSet).contains("message_count = message_count + 1");
    }
}
