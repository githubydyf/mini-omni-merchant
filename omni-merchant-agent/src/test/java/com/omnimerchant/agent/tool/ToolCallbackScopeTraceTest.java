package com.omnimerchant.agent.tool;

import com.omnimerchant.agent.context.CallContextHolder;
import com.omnimerchant.agent.context.CallScope;
import com.omnimerchant.agent.context.TraceContextHolder;
import com.omnimerchant.tenant.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 ToolCallbackScope：
 * <ul>
 *   <li>工具在其它线程执行时仍能读到本轮 traceId；</li>
 *   <li>已由 ToolAuditService 记账的工具不重复补记；</li>
 *   <li>未记账的工具（PolicyTools）补记一条真实 TOOL 轨迹；</li>
 *   <li>执行后恢复现场，不污染线程池线程。</li>
 * </ul>
 */
class ToolCallbackScopeTraceTest {

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        CallContextHolder.clear();
        TraceContextHolder.clear();
        ToolTraceMarker.clear();
    }

    private ToolCallback tool(String name, StringBuilder seenTrace) {
        var definition = ToolDefinition.builder()
                .name(name).description("测试桩").inputSchema("{\"type\":\"object\"}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                // 模拟工具执行时读取 traceId 上下文
                seenTrace.append(TraceContextHolder.get());
                return "{\"ok\":true}";
            }
        };
    }

    @Test
    void toolShouldSeeTraceIdFromScope() {
        var seen = new StringBuilder();
        var wrapped = ToolCallbackScope.wrap(tool("queryOrder", seen),
                new CallScope(1001L, "ORDER_STATUS", "conv-1", "trace-abc"));

        wrapped.call("{}");

        assertThat(seen.toString()).isEqualTo("trace-abc");
        assertThat(TraceContextHolder.get()).isNull();
    }

    @Test
    void unauditedToolShouldBeBackfilled() {
        var seen = new StringBuilder();
        List<ToolCallbackScope.ToolTrace> recorded = new ArrayList<>();
        var wrapped = ToolCallbackScope.wrap(tool("refundPolicyRAG", seen),
                new CallScope(1001L, "POLICY_QA", "conv-1", "trace-abc"), recorded::add);

        wrapped.call("{\"question\":\"退货期限\"}");

        // PolicyTools 未走审计 → 应补记一条
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).toolName()).isEqualTo("refundPolicyRAG");
        assertThat(recorded.get(0).traceId()).isEqualTo("trace-abc");
        assertThat(recorded.get(0).success()).isTrue();
    }

    @Test
    void auditedToolShouldNotBeBackfilled() {
        var seen = new StringBuilder();
        List<ToolCallbackScope.ToolTrace> recorded = new ArrayList<>();
        var definition = ToolDefinition.builder()
                .name("queryOrder").description("桩").inputSchema("{\"type\":\"object\"}").build();
        ToolCallback audited = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                // 模拟 ToolAuditService 记账后打标记
                ToolTraceMarker.mark("call-x");
                return "{}";
            }
        };
        var wrapped = ToolCallbackScope.wrap(audited,
                new CallScope(1001L, "ORDER_STATUS", "conv-1", "trace-abc"), recorded::add);

        wrapped.call("{}");

        // 审计层已记账 → 包装器不得重复补记
        assertThat(recorded).isEmpty();
    }

    @Test
    void failingToolShouldBeBackfilledAsFailed() {
        List<ToolCallbackScope.ToolTrace> recorded = new ArrayList<>();
        var definition = ToolDefinition.builder()
                .name("refundPolicyRAG").description("桩").inputSchema("{\"type\":\"object\"}").build();
        ToolCallback failing = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("政策检索失败");
            }
        };
        var wrapped = ToolCallbackScope.wrap(failing,
                new CallScope(1001L, "POLICY_QA", "conv-1", "trace-abc"), recorded::add);

        try {
            wrapped.call("{}");
        } catch (IllegalStateException expected) {
            // 预期异常向外抛出
        }

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).success()).isFalse();
    }
}
