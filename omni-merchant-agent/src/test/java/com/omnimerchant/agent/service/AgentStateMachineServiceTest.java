package com.omnimerchant.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.entity.AgentConversationState;
import com.omnimerchant.agent.entity.AgentStateTransition;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.mapper.AgentConversationStateMapper;
import com.omnimerchant.agent.mapper.AgentStateTransitionMapper;
import com.omnimerchant.agent.mapper.ConversationMapper;
import com.omnimerchant.tenant.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentStateMachineService 单元测试（对应任务 §24）。
 *
 * <p>覆盖：状态创建与推进、合法/非法转换、CLOSED 拒绝、租户隔离与校验、
 * version 乐观锁冲突、唯一键并发、工具结果驱动的状态推进、人工接管保护。
 */
class AgentStateMachineServiceTest {

    private final AgentConversationStateMapper stateMapper = mock(AgentConversationStateMapper.class);
    private final AgentStateTransitionMapper transitionMapper = mock(AgentStateTransitionMapper.class);
    private final ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private AgentStateMachineService service;

    @BeforeAll
    static void initTableInfo() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, AgentConversationState.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, AgentStateTransition.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Conversation.class);
    }

    @BeforeEach
    void setUp() {
        service = new AgentStateMachineService(stateMapper, transitionMapper, conversationMapper,
                new ObjectMapper());
        TenantContextHolder.set(1001L);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    // ---------- 工具方法 ----------

    private Conversation aiEligibleConversation(int status) {
        var c = new Conversation();
        c.setId(1L);
        c.setConversationUuid("conv-1");
        c.setTenantId(1001L);
        c.setStatus(status);
        return c;
    }

    private AgentConversationState state(String value, int version) {
        var s = new AgentConversationState();
        s.setId(1L);
        s.setTenantId(1001L);
        s.setConversationUuid("conv-1");
        s.setState(value);
        s.setVersion(version);
        return s;
    }

    private void givenConversation(int status) {
        when(conversationMapper.selectOne(any())).thenReturn(aiEligibleConversation(status));
    }

    private void givenState(AgentConversationState s) {
        when(stateMapper.selectOne(any())).thenReturn(s);
    }

    private void givenUpdateOk() {
        when(stateMapper.update(isNull(), any())).thenReturn(1);
    }

    // ---------- 1. 首次运行 ----------

    @Test
    void firstRunShouldCreateNewStateThenTriageAndWorking() {
        givenConversation(1);
        when(stateMapper.selectOne(any())).thenReturn(null);   // 首次不存在
        // 记录「插入那一刻」的状态：之后 transition 会复用同一对象并修改其 state
        var insertedState = new java.util.concurrent.atomic.AtomicReference<String>();
        when(stateMapper.insert(any(AgentConversationState.class))).thenAnswer(inv -> {
            AgentConversationState s = inv.getArgument(0);
            insertedState.set(s.getState());
            s.setId(1L);
            return 1;
        });
        givenUpdateOk();

        service.startRun(1001L, "conv-1", "trace-1", "order");

        assertThat(insertedState.get()).isEqualTo("NEW");

        // NEW → AI_TRIAGE → AI_WORKING 两条转换历史
        var captor = ArgumentCaptor.forClass(AgentStateTransition.class);
        verify(transitionMapper, times(2)).insert(captor.capture());
        var transitions = captor.getAllValues();
        assertThat(transitions).extracting(AgentStateTransition::getFromState)
                .containsExactly("NEW", "AI_TRIAGE");
        assertThat(transitions).extracting(AgentStateTransition::getToState)
                .containsExactly("AI_TRIAGE", "AI_WORKING");
        assertThat(transitions).allMatch(t -> "trace-1".equals(t.getTraceId()));
        assertThat(transitions).allMatch(t -> "conv-1".equals(t.getConversationUuid()));
    }

    @Test
    void existingWaitingCustomerShouldResumeToTriageAndWorking() {
        givenConversation(2);
        givenState(state("WAITING_CUSTOMER", 5));
        givenUpdateOk();

        service.startRun(1001L, "conv-1", "trace-2", "order");

        var captor = ArgumentCaptor.forClass(AgentStateTransition.class);
        verify(transitionMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(AgentStateTransition::getFromState)
                .containsExactly("WAITING_CUSTOMER", "AI_TRIAGE");
    }

    // ---------- 2. 正常回复收尾 ----------

    @Test
    void completeRunShouldMoveWorkingToWaitingCustomer() {
        givenState(state("AI_WORKING", 2));
        givenUpdateOk();

        service.completeRun(1001L, "conv-1", "trace-1");

        var captor = ArgumentCaptor.forClass(AgentStateTransition.class);
        verify(transitionMapper).insert(captor.capture());
        assertThat(captor.getValue().getFromState()).isEqualTo("AI_WORKING");
        assertThat(captor.getValue().getToState()).isEqualTo("WAITING_CUSTOMER");
    }

    @Test
    void completeRunShouldNotOverrideApprovalState() {
        // 本轮已真实创建审批申请 → NEEDS_APPROVAL，正常回复结束不得覆盖
        givenState(state("NEEDS_APPROVAL", 3));
        service.completeRun(1001L, "conv-1", "trace-1");
        verify(stateMapper, never()).update(any(), any());
        verify(transitionMapper, never()).insert(any(AgentStateTransition.class));
    }

    @Test
    void completeRunShouldNotOverrideHumanAssignedState() {
        givenState(state("HUMAN_ASSIGNED", 3));
        service.completeRun(1001L, "conv-1", "trace-1");
        verify(transitionMapper, never()).insert(any(AgentStateTransition.class));
    }

    // ---------- 3. 失败收尾 ----------

    @Test
    void failRunShouldRecordReasonAndMoveToWaitingCustomer() {
        givenState(state("AI_WORKING", 2));
        givenUpdateOk();

        service.failRun(1001L, "conv-1", "trace-1", "模型异常");

        var captor = ArgumentCaptor.forClass(AgentStateTransition.class);
        verify(transitionMapper).insert(captor.capture());
        assertThat(captor.getValue().getToState()).isEqualTo("WAITING_CUSTOMER");
        assertThat(captor.getValue().getTriggerType()).isEqualTo("FAILURE");
    }

    @Test
    void failRunShouldNotUndoRealBusinessWhenNotWorking() {
        givenState(state("NEEDS_APPROVAL", 3));
        service.failRun(1001L, "conv-1", "trace-1", "模型异常");
        // 已进入审批等待的业务不能被失败收尾撤销
        verify(transitionMapper, never()).insert(any(AgentStateTransition.class));
    }

    // ---------- 4. 工具结果驱动状态 ----------

    @Test
    void refundToolWithPendingApprovalShouldEnterNeedsApproval() {
        givenState(state("AI_WORKING", 2));
        givenUpdateOk();

        service.toolSucceeded(1001L, "conv-1", "trace-1", "requestRefundOrReplacement",
                "{\"requestNo\":\"ACT-1\",\"status\":\"PENDING_HUMAN_APPROVAL\",\"orderNumber\":\"#1002\"}");

        var captor = ArgumentCaptor.forClass(AgentStateTransition.class);
        verify(transitionMapper).insert(captor.capture());
        assertThat(captor.getValue().getToState()).isEqualTo("NEEDS_APPROVAL");
        assertThat(captor.getValue().getTriggerType()).isEqualTo("TOOL");
    }

    @Test
    void refundToolWithIdentityRequiredShouldEnterNeedsCustomerVerify() {
        givenState(state("AI_WORKING", 2));
        givenUpdateOk();

        service.toolSucceeded(1001L, "conv-1", "trace-1", "requestRefundOrReplacement",
                "{\"status\":\"IDENTITY_VERIFICATION_REQUIRED\",\"orderNumber\":\"#1002\"}");

        var captor = ArgumentCaptor.forClass(AgentStateTransition.class);
        verify(transitionMapper).insert(captor.capture());
        assertThat(captor.getValue().getToState()).isEqualTo("NEEDS_CUSTOMER_VERIFY");
    }

    @Test
    void rejectedToolShouldNotCreateFakeApprovalTransition() {
        givenState(state("AI_WORKING", 2));
        // 仅查询/被拒绝：没有 PENDING_HUMAN_APPROVAL
        service.toolSucceeded(1001L, "conv-1", "trace-1", "requestRefundOrReplacement",
                "{\"status\":\"ORDER_NOT_FOUND\"}");
        verify(transitionMapper, never()).insert(any(AgentStateTransition.class));
    }

    @Test
    void escalationSuccessShouldEnterHumanAssigned() {
        givenState(state("AI_WORKING", 2));
        givenUpdateOk();

        service.toolSucceeded(1001L, "conv-1", "trace-1", "escalateToHuman",
                "{\"ticketId\":\"TKT-1\",\"status\":\"PENDING\"}");

        var captor = ArgumentCaptor.forClass(AgentStateTransition.class);
        verify(transitionMapper).insert(captor.capture());
        assertThat(captor.getValue().getToState()).isEqualTo("HUMAN_ASSIGNED");
    }

    @Test
    void escalationUnavailableShouldNotEnterHumanAssigned() {
        givenState(state("AI_WORKING", 2));
        service.toolSucceeded(1001L, "conv-1", "trace-1", "escalateToHuman",
                "{\"ticketId\":\"UNAVAILABLE\",\"status\":\"UNAVAILABLE\"}");
        verify(transitionMapper, never()).insert(any(AgentStateTransition.class));
    }

    @Test
    void unparsableToolOutputShouldNotGuess() {
        givenState(state("AI_WORKING", 2));
        service.toolSucceeded(1001L, "conv-1", "trace-1", "escalateToHuman", "不是JSON");
        verify(transitionMapper, never()).insert(any(AgentStateTransition.class));
    }

    // ---------- 5. 关闭与人工接管 ----------

    @Test
    void closedConversationShouldRejectStartRun() {
        givenConversation(1);
        givenState(state("CLOSED", 9));

        assertThatThrownBy(() -> service.startRun(1001L, "conv-1", "trace-1", "order"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    void humanTakeoverConversationShouldRejectAiStart() {
        // conversation.status=4（人工处理中）时 stateMachine 拒绝启动
        givenConversation(4);
        assertThatThrownBy(() -> service.startRun(1001L, "conv-1", "trace-1", "order"))
                .isInstanceOf(com.omnimerchant.common.exception.BusinessException.class)
                .hasMessageContaining("人工接管");
    }

    @Test
    void closedConversationStatusShouldRejectAiStart() {
        givenConversation(5);
        assertThatThrownBy(() -> service.startRun(1001L, "conv-1", "trace-1", "order"))
                .isInstanceOf(com.omnimerchant.common.exception.BusinessException.class);
    }

    // ---------- 6. 非法转换 / 并发 ----------

    @Test
    void illegalTransitionShouldThrowExplicitly() {
        // HUMAN_ASSIGNED 不允许回到 AI_TRIAGE（与参考项目的差异）
        givenConversation(2);
        givenState(state("HUMAN_ASSIGNED", 3));

        assertThatThrownBy(() -> service.startRun(1001L, "conv-1", "trace-1", "order"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_TRIAGE");
    }

    @Test
    void versionConflictShouldRejectConcurrentUpdate() {
        givenState(state("AI_WORKING", 2));
        when(stateMapper.update(isNull(), any())).thenReturn(0);  // 影响行数 0 = 并发修改

        assertThatThrownBy(() -> service.completeRun(1001L, "conv-1", "trace-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("并发");
        // 状态更新失败时不得写入转换历史
        verify(transitionMapper, never()).insert(any(AgentStateTransition.class));
    }

    @Test
    void tenantMismatchShouldFailBeforeDatabaseAccess() {
        TenantContextHolder.set(1002L);   // 上下文租户与传入 tenantId 不一致
        assertThatThrownBy(() -> service.startRun(1001L, "conv-1", "trace-1", "order"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void missingTenantContextShouldFail() {
        TenantContextHolder.clear();
        assertThatThrownBy(() -> service.startRun(1001L, "conv-1", "trace-1", "order"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void concurrentStateCreationShouldFallBackToReadExisting() {
        givenConversation(1);
        // 第一次查为空 → 插入冲突 → 再查得到已存在行
        var existing = state("NEW", 0);
        when(stateMapper.selectOne(any())).thenReturn(null, existing);
        when(stateMapper.insert(any(AgentConversationState.class)))
                .thenThrow(new DuplicateKeyException("uk_agent_conversation_state"));
        givenUpdateOk();

        // 不抛异常，继续在既有行上推进
        service.startRun(1001L, "conv-1", "trace-1", "order");

        verify(transitionMapper, times(2)).insert(any(AgentStateTransition.class));
    }

    // ---------- 7. 租户隔离 ----------

    @Test
    void stateQueryShouldBeTenantScoped() {
        TenantContextHolder.set(1002L);
        when(stateMapper.selectOne(any())).thenReturn(null);
        // 不同租户互不影响：1002 查询自己租户的会话返回 NEW
        assertThat(service.currentState(1002L, "conv-x")).isEqualTo("NEW");
    }

    @Test
    void currentStateShouldReturnNewWhenAbsent() {
        when(stateMapper.selectOne(any())).thenReturn(null);
        assertThat(service.currentState(1001L, "conv-new")).isEqualTo("NEW");
    }

    @Test
    void transitionsHistoryShouldBeReadable() {
        var t = new AgentStateTransition();
        t.setFromState("NEW");
        t.setToState("AI_TRIAGE");
        when(transitionMapper.selectList(any())).thenReturn(List.of(t));

        var history = service.recentTransitions(1001L, "conv-1", 10);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).getToState()).isEqualTo("AI_TRIAGE");
    }
}
