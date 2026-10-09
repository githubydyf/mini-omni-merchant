package com.omnimerchant.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.entity.ToolCallLog;
import com.omnimerchant.agent.mapper.ToolCallLogMapper;
import com.omnimerchant.tenant.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 ToolAuditService 的审计行为（不依赖 Spring 容器）：
 * 成功写 success=1；业务异常写 success=0 + TOOL_EXCEPTION 并重新抛出；
 * 审计写库自身失败不影响业务返回。
 */
class ToolAuditServiceTest {

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void shouldRecordSuccessWithResultAndMetadata() {
        var mapper = mock(ToolCallLogMapper.class);
        var service = new ToolAuditService(mapper, new ObjectMapper(), mock(AgentTraceService.class));
        TenantContextHolder.set(1001L);

        var result = service.record("queryOrder", params("orderId", "#1001"), () -> "OK");

        assertThat(result).isEqualTo("OK");
        var captor = ArgumentCaptor.forClass(ToolCallLog.class);
        verify(mapper).insert(captor.capture());
        var log = captor.getValue();
        assertThat(log.getSuccess()).isEqualTo(1);
        assertThat(log.getToolName()).isEqualTo("queryOrder");
        assertThat(log.getToolVersion()).isEqualTo("v1");
        assertThat(log.getTenantId()).isEqualTo(1001L);
        assertThat(log.getErrorCode()).isNull();
        assertThat(log.getResult()).isNotNull();
        assertThat(log.getParamsHash()).isNotNull();
        assertThat(log.getLatencyMs()).isNotNull();
        assertThat(log.getCacheHit()).isEqualTo(0);
        assertThat(log.getRetryCount()).isEqualTo(0);
        assertThat(log.getIsRetry()).isEqualTo(0);
        assertThat(log.getStartedAt()).isNotNull();
        assertThat(log.getEndedAt()).isNotNull();
    }

    @Test
    void shouldRecordFailureAndRethrow() {
        var mapper = mock(ToolCallLogMapper.class);
        var service = new ToolAuditService(mapper, new ObjectMapper(), mock(AgentTraceService.class));

        assertThatThrownBy(() -> service.record("queryOrder", params("orderId", "#1"), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        var captor = ArgumentCaptor.forClass(ToolCallLog.class);
        verify(mapper).insert(captor.capture());
        var log = captor.getValue();
        assertThat(log.getSuccess()).isEqualTo(0);
        assertThat(log.getErrorCode()).isEqualTo("TOOL_EXCEPTION");
        assertThat(log.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    void auditWriteFailureMustNotBreakBusinessResult() {
        var mapper = mock(ToolCallLogMapper.class);
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(ToolCallLog.class));
        var service = new ToolAuditService(mapper, new ObjectMapper(), mock(AgentTraceService.class));

        // 审计写库失败，业务结果照常返回
        var result = service.record("queryOrder", params("orderId", "#1001"), () -> "OK");

        assertThat(result).isEqualTo("OK");
        verify(mapper, times(1)).insert(any(ToolCallLog.class));
    }

    private Map<String, Object> params(Object... entries) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }
}
