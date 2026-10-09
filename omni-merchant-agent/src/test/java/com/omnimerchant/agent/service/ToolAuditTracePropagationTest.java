package com.omnimerchant.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.context.CallContextHolder;
import com.omnimerchant.agent.context.CallScope;
import com.omnimerchant.agent.context.TraceContextHolder;
import com.omnimerchant.agent.entity.ToolCallLog;
import com.omnimerchant.agent.mapper.ToolCallLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 验证 ToolAuditService 使用本轮 AgentRun 的 traceId（而不是随机 UUID），
 * 即使工具运行在其它线程（由 CallScope 绑定上下文）。
 */
class ToolAuditTracePropagationTest {

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
        CallContextHolder.clear();
    }

    @Test
    void auditShouldUseTraceIdFromToolThreadScope() {
        var mapper = mock(ToolCallLogMapper.class);
        var service = new ToolAuditService(mapper, new ObjectMapper(), mock(AgentTraceService.class));
        var scope = new CallScope(1001L, "ORDER_STATUS", "conv-1", "trace-run-1");

        // 在当前线程无任何上下文（模拟工具线程），由 scope 绑定后执行审计
        scope.runInScope(() -> service.record("queryOrder", params("orderId", "#1001"), () -> "{}"));

        var captor = ArgumentCaptor.forClass(ToolCallLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getTraceId()).isEqualTo("trace-run-1");
        assertThat(captor.getValue().getConversationUuid()).isEqualTo("conv-1");
    }

    @Test
    void auditShouldFallbackToRandomTraceIdWithoutScope() {
        var mapper = mock(ToolCallLogMapper.class);
        var service = new ToolAuditService(mapper, new ObjectMapper(), mock(AgentTraceService.class));

        service.record("queryOrder", params("orderId", "#1001"), () -> "{}");

        var captor = ArgumentCaptor.forClass(ToolCallLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getTraceId()).isNotBlank();
        assertThat(captor.getValue().getTraceId()).isNotEqualTo("trace-run-1");
    }

    private java.util.Map<String, Object> params(String key, Object value) {
        var map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }
}
