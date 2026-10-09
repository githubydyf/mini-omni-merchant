package com.omnimerchant.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.entity.AgentRun;
import com.omnimerchant.agent.entity.AgentStep;
import com.omnimerchant.agent.entity.ToolCallLog;
import com.omnimerchant.agent.mapper.AgentRunMapper;
import com.omnimerchant.agent.mapper.AgentStepMapper;
import com.omnimerchant.agent.mapper.ToolCallLogMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentTraceService 单元测试：Run 生命周期、traceId 唯一、脱敏、失败路径、stepIndex。
 */
class AgentTraceServiceTest {

    private final AgentRunMapper runMapper = mock(AgentRunMapper.class);
    private final AgentStepMapper stepMapper = mock(AgentStepMapper.class);
    private final ToolCallLogMapper toolCallLogMapper = mock(ToolCallLogMapper.class);
    private final FailureAttributionService failureAttributionService = new FailureAttributionService();
    private AgentTraceService service;

    /** LambdaQueryWrapper 依赖 MyBatis-Plus 实体元信息，单测需手动初始化。 */
    @BeforeAll
    static void initTableInfo() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, AgentRun.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, AgentStep.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ToolCallLog.class);
    }

    @BeforeEach
    void setUp() {
        service = new AgentTraceService(runMapper, stepMapper, toolCallLogMapper,
                failureAttributionService, new ObjectMapper());
    }

    private AgentRun run(String traceId) {
        var run = new AgentRun();
        run.setId(1L);
        run.setTenantId(1001L);
        run.setTraceId(traceId);
        run.setConversationUuid("conv-1");
        run.setStatus("RUNNING");
        return run;
    }

    @Test
    void startChatRunShouldInsertRunningRunWithRealFields() {
        var traceId = service.startChatRun(1001L, "conv-1", "POLICY_QA",
                "deepseek", "deepseek-flash", "退货期限是多少天？");

        assertThat(traceId).isNotBlank();
        var captor = ArgumentCaptor.forClass(AgentRun.class);
        verify(runMapper).insert(captor.capture());
        var run = captor.getValue();
        assertThat(run.getStatus()).isEqualTo("RUNNING");
        assertThat(run.getTenantId()).isEqualTo(1001L);
        assertThat(run.getConversationUuid()).isEqualTo("conv-1");
        assertThat(run.getIntent()).isEqualTo("POLICY_QA");
        assertThat(run.getModelName()).isEqualTo("deepseek-flash");
        assertThat(run.getRunType()).isEqualTo("CHAT");
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getTraceId()).isEqualTo(traceId);
        // 不伪造指标
        assertThat(run.getPromptTokens()).isZero();
        assertThat(run.getCostUsd()).isEqualByComparingTo("0");
    }

    @Test
    void eachRunShouldGetUniqueTraceId() {
        var a = service.startChatRun(1001L, "conv-1", "ORDER_STATUS", "deepseek", "deepseek-flash", "问题1");
        var b = service.startChatRun(1001L, "conv-1", "LOGISTICS", "deepseek", "deepseek-flash", "问题2");
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isNotEqualTo("conv-1");
    }

    @Test
    void startChatRunShouldRedactAndHashInput() {
        service.startChatRun(1001L, "conv-1", "ORDER_STATUS", "deepseek", "deepseek-flash",
                "查询订单 #1001，邮箱是 ava@example.com，电话是 13800138000，key sk-abcdefgh12345678");

        var captor = ArgumentCaptor.forClass(AgentRun.class);
        verify(runMapper).insert(captor.capture());
        var run = captor.getValue();
        assertThat(run.getInputRedacted()).doesNotContain("ava@example.com");
        assertThat(run.getInputRedacted()).doesNotContain("13800138000");
        assertThat(run.getInputRedacted()).doesNotContain("sk-abcdefgh12345678");
        assertThat(run.getInputRedacted()).contains("[email]");
        // hash 不能代替脱敏，但确实存在
        assertThat(run.getInputHash()).hasSize(64);
    }

    @Test
    void addStepShouldAssignIncrementingIndex() {
        when(runMapper.selectOne(any())).thenReturn(run("trace-1"));
        when(stepMapper.selectCount(any())).thenReturn(0L, 1L, 2L);

        service.addStep("trace-1", "ROUTER", "supervisor_worker_plan", "SUCCESS", "a", "b", null, 1, Map.of());
        service.addStep("trace-1", "MEMORY", "load_recent_messages", "SUCCESS", "a", "b", null, 1, Map.of());
        service.addStep("trace-1", "TOOL", "queryOrder", "SUCCESS", "a", "b", "call-1", 1, Map.of());

        var captor = ArgumentCaptor.forClass(AgentStep.class);
        verify(stepMapper, atLeastOnce()).insert(captor.capture());
        var steps = captor.getAllValues();
        assertThat(steps).extracting(AgentStep::getStepIndex).containsExactly(1, 2, 3);
        assertThat(steps).extracting(AgentStep::getStepType)
                .containsExactly("ROUTER", "MEMORY", "TOOL");
        assertThat(steps.get(0).getAgentRunId()).isEqualTo(1L);
        assertThat(steps.get(0).getTraceId()).isEqualTo("trace-1");
    }

    @Test
    void addStepShouldSkipWhenRunMissing() {
        when(runMapper.selectOne(any())).thenReturn(null);
        service.addStep("unknown", "ROUTER", "x", "SUCCESS", "a", "b", null, 1, Map.of());
        verify(stepMapper, never()).insert(any(AgentStep.class));
    }

    @Test
    void completeRunShouldMarkSuccessAndCountToolsFromRealLog() {
        when(runMapper.selectOne(any())).thenReturn(run("trace-1"));
        when(stepMapper.selectCount(any())).thenReturn(2L);
        when(toolCallLogMapper.selectCount(any())).thenReturn(1L);

        service.completeRun("trace-1", "答案是七天。", 120, 800);

        verify(runMapper).update(any(), any());
        var captor = ArgumentCaptor.forClass(AgentStep.class);
        verify(stepMapper).insert(captor.capture());
        assertThat(captor.getValue().getStepType()).isEqualTo("FINAL_ANSWER");
        assertThat(captor.getValue().getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void failRunShouldMarkFailedAndRecordFailureStep() {
        when(runMapper.selectOne(any())).thenReturn(run("trace-1"));
        when(stepMapper.selectCount(any())).thenReturn(1L);

        service.failRun("trace-1", new java.util.concurrent.TimeoutException("请求超时"), 500);

        verify(runMapper).update(any(), any());
        var captor = ArgumentCaptor.forClass(AgentStep.class);
        verify(stepMapper).insert(captor.capture());
        assertThat(captor.getValue().getStepType()).isEqualTo("FAILURE");
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getFailureCategory()).isEqualTo("LLM_TIMEOUT");
    }

    @Test
    void failRunIfStillRunningShouldSkipWhenAlreadySuccessful() {
        var finished = run("trace-1");
        finished.setStatus("SUCCESS");
        when(runMapper.selectOne(any())).thenReturn(finished);

        service.failRunIfStillRunning("trace-1", "CANCELLED", "客户端中断", 100);

        // 已成功的 Run 不能被取消覆盖
        verify(runMapper, never()).update(any(), any());
    }

    @Test
    void recordToolStepShouldMapToToolStep() {
        when(runMapper.selectOne(any())).thenReturn(run("trace-1"));
        when(stepMapper.selectCount(any())).thenReturn(0L);

        var log = new ToolCallLog();
        log.setTraceId("trace-1");
        log.setToolName("queryOrder");
        log.setToolCallId("call-1");
        log.setSuccess(1);
        log.setParams("{\"orderId\":\"#1001\"}");
        log.setResult("{}");
        log.setLatencyMs(42);

        service.recordToolStep(log);

        var captor = ArgumentCaptor.forClass(AgentStep.class);
        verify(stepMapper).insert(captor.capture());
        assertThat(captor.getValue().getStepType()).isEqualTo("TOOL");
        assertThat(captor.getValue().getName()).isEqualTo("queryOrder");
        assertThat(captor.getValue().getToolCallId()).isEqualTo("call-1");
    }

    @Test
    void traceWriteFailureShouldNotThrow() {
        when(runMapper.selectOne(any())).thenThrow(new RuntimeException("数据库不可用"));
        // 轨迹写入失败必须被吞掉，不能影响业务
        service.addStep("trace-1", "ROUTER", "x", "SUCCESS", "a", "b", null, 1, Map.of());
    }

    @Test
    void getTraceShouldReturnNullWhenMissing() {
        when(runMapper.selectOne(any())).thenReturn(null);
        assertThat(service.getTrace("nope")).isNull();
    }

    @Test
    void getTraceShouldReturnRunWithOrderedSteps() {
        when(runMapper.selectOne(any())).thenReturn(run("trace-1"));
        var step = new AgentStep();
        step.setStepIndex(1);
        step.setStepType("ROUTER");
        step.setName("supervisor_worker_plan");
        step.setStatus("SUCCESS");
        when(stepMapper.selectList(any())).thenReturn(List.of(step));

        var detail = service.getTrace("trace-1");

        assertThat(detail).isNotNull();
        assertThat(detail.run().traceId()).isEqualTo("trace-1");
        assertThat(detail.steps()).hasSize(1);
        assertThat(detail.steps().get(0).stepType()).isEqualTo("ROUTER");
    }
}
