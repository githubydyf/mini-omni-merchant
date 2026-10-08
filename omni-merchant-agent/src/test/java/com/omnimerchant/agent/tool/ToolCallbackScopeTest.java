package com.omnimerchant.agent.tool;

import com.omnimerchant.agent.context.CallContextHolder;
import com.omnimerchant.agent.context.CallScope;
import com.omnimerchant.tenant.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 ToolCallbackScope 让工具在"非请求线程"上也能读到正确上下文，
 * 且执行后恢复现场（不污染线程池线程）。
 */
class ToolCallbackScopeTest {

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        CallContextHolder.clear();
    }

    private ToolCallback capture(StringBuilder sink) {
        var definition = ToolDefinition.builder()
                .name("queryOrder")
                .description("测试桩")
                .inputSchema("{\"type\":\"object\"}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                var call = CallContextHolder.get();
                sink.append(TenantContextHolder.get()).append("|")
                        .append(call == null ? "null" : call.conversationUuid());
                return "ok";
            }
        };
    }

    @Test
    void wrappedCallbackShouldSeeScopeContext() {
        var sink = new StringBuilder();
        var wrapped = ToolCallbackScope.wrap(capture(sink), new CallScope(1001L, "POLICY_QA", "conv-x"));

        // 主线程不设置任何上下文，模拟工具在 boundedElastic 线程执行
        wrapped.call("{}");

        assertThat(sink.toString()).isEqualTo("1001|conv-x");
        assertThat(TenantContextHolder.get()).isNull();
        assertThat(CallContextHolder.get()).isNull();
    }

    @Test
    void wrappedCallbackShouldPassThroughToolContextOverload() {
        var sink = new StringBuilder();
        var delegate = capture(sink);
        var wrapped = ToolCallbackScope.wrap(delegate, new CallScope(2002L, "ORDER_STATUS", "conv-y"));

        wrapped.call("{}", new ToolContext(Map.of("k", "v")));

        assertThat(sink.toString()).isEqualTo("2002|conv-y");
    }

    @Test
    void delegateToolDefinitionShouldBePreserved() {
        var wrapped = ToolCallbackScope.wrap(capture(new StringBuilder()),
                new CallScope(1L, "x", "y"));
        assertThat(wrapped.getToolDefinition().name()).isEqualTo("queryOrder");
    }
}
