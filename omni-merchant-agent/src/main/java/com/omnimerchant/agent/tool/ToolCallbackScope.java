package com.omnimerchant.agent.tool;

import com.omnimerchant.agent.context.CallScope;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 把 Tool 执行绑定到正确的请求上下文，并为「未走审计的工具」补记真实轨迹。
 *
 * <p>为什么必须这么做：Spring AI 的 {@code ToolCallingAdvisor} 使用
 * {@code subscribeOn(Schedulers.boundedElastic())} 执行工具调用，工具实际运行在
 * Reactor 的 boundedElastic 线程上，<b>普通 ThreadLocal 不会自动传播</b>。
 * 若不处理，ToolAuditService 将拿不到 conversationUuid / tenantId / traceId。
 *
 * <p>这里不去依赖 Reactor 的上下文传播机制，而是直接把"当前请求的上下文"
 * 闭包捕获进回调，在工具真正执行的那一刻绑定到执行线程，执行完恢复现场。
 *
 * <p>轨迹职责（任务 §18）：同一次 Tool 调用只能产生一条 TOOL 步。
 * <ul>
 *   <li>已走 {@code ToolAuditService} 的工具（Order/Logistics/Product/Escalation）：
 *       审计层写 tool_call_log 后同步记录 TOOL 步并打标记，本包装器不重复记录；</li>
 *   <li>未走审计的工具（knowledge 模块的 PolicyTools）：本包装器补记一条真实 TOOL 步，
 *       使政策检索也有轨迹，且<b>不引入 knowledge → agent 的依赖方向</b>。</li>
 * </ul>
 */
public final class ToolCallbackScope implements ToolCallback {

    private final ToolCallback delegate;
    private final CallScope scope;
    private final ToolTraceRecorder traceRecorder;

    private ToolCallbackScope(ToolCallback delegate, CallScope scope, ToolTraceRecorder traceRecorder) {
        this.delegate = delegate;
        this.scope = scope;
        this.traceRecorder = traceRecorder;
    }

    /** 用给定作用域包装一个 ToolCallback（不补记轨迹）。 */
    public static ToolCallback wrap(ToolCallback delegate, CallScope scope) {
        return new ToolCallbackScope(delegate, scope, null);
    }

    /** 用给定作用域包装，并在需要时为未审计工具补记轨迹。 */
    public static ToolCallback wrap(ToolCallback delegate, CallScope scope, ToolTraceRecorder traceRecorder) {
        return new ToolCallbackScope(delegate, scope, traceRecorder);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return invoke(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return invoke(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    /**
     * 在作用域内执行工具：进入前清掉可能残留的「已记账」标记，
     * 执行后若无标记则补记一条真实 TOOL 步。
     */
    private String invoke(String toolInput, Supplier<String> invocation) {
        ToolTraceMarker.clear();
        var startedAt = LocalDateTime.now();
        var callId = UUID.randomUUID().toString();
        String result = null;
        RuntimeException failure = null;
        try {
            result = scope.runInScope(invocation::get);
            return result;
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try {
                var latencyMs = (int) Duration.between(startedAt, LocalDateTime.now()).toMillis();
                // 审计层已记账则不重复；否则由本包装器补记（典型为 PolicyTools）
                var alreadyRecorded = ToolTraceMarker.consume();
                if (traceRecorder != null && alreadyRecorded == null) {
                    traceRecorder.record(new ToolTrace(
                            scope.traceId(), scope.conversationUuid(),
                            delegate.getToolDefinition().name(), callId,
                            failure == null, toolInput,
                            failure == null ? result : failure.getMessage(), latencyMs));
                }
            } finally {
                ToolTraceMarker.clear();
            }
        }
    }

    /** 一条待记录的 Tool 轨迹（由包装器产出，交给 Agent 侧服务落库）。 */
    public record ToolTrace(
            String traceId,
            String conversationUuid,
            String toolName,
            String toolCallId,
            boolean success,
            String input,
            String output,
            Integer latencyMs) {
    }

    /** 轨迹记录回调：由 agent 模块实现，避免 knowledge → agent 的循环依赖。 */
    @FunctionalInterface
    public interface ToolTraceRecorder {
        void record(ToolTrace toolTrace);
    }
}
