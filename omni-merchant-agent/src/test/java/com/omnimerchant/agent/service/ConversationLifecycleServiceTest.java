package com.omnimerchant.agent.service;

import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.mapper.ConversationMapper;
import com.omnimerchant.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话生命周期测试：真实创建、会话不存在、租户不一致、状态不允许 AI 回复。
 */
class ConversationLifecycleServiceTest {

    private final ConversationMapper mapper = mock(ConversationMapper.class);
    private final ConversationLifecycleService service = new ConversationLifecycleService(mapper);

    @Test
    void createShouldAssignUuidTenantAndActiveStatus() {
        var created = service.create(1001L, null, null, null);

        assertThat(created.getConversationUuid()).isNotBlank();
        assertThat(created.getTenantId()).isEqualTo(1001L);
        assertThat(created.getChannel()).isEqualTo("WEB");
        assertThat(created.getStatus()).isEqualTo(1);
        assertThat(created.getMessageCount()).isEqualTo(0);
        assertThat(created.getStartedAt()).isNotNull();
    }

    @Test
    void createWithoutTenantShouldFail() {
        assertThatThrownBy(() -> service.create(null, "WEB", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("租户");
    }

    @Test
    void missingConversationShouldBeRejected() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.requireForAi("nope", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    void tenantMismatchShouldBeRejected() {
        when(mapper.selectOne(any())).thenReturn(conversation(1001L, 1));
        assertThatThrownBy(() -> service.requireForAi("conv-1", 2002L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("租户");
    }

    @Test
    void humanTakeoverStatusShouldBeRejected() {
        when(mapper.selectOne(any())).thenReturn(conversation(1001L, 4));
        assertThatThrownBy(() -> service.requireForAi("conv-1", 1001L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("人工接管");
    }

    @Test
    void closedConversationShouldBeRejected() {
        when(mapper.selectOne(any())).thenReturn(conversation(1001L, 5));
        assertThatThrownBy(() -> service.requireForAi("conv-1", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void activeConversationShouldPass() {
        when(mapper.selectOne(any())).thenReturn(conversation(1001L, 1));
        var result = service.requireForAi("conv-1", 1001L);
        assertThat(result.getTenantId()).isEqualTo(1001L);
    }

    private Conversation conversation(Long tenantId, int status) {
        var c = new Conversation();
        c.setId(1L);
        c.setConversationUuid("conv-1");
        c.setTenantId(tenantId);
        c.setStatus(status);
        return c;
    }
}
