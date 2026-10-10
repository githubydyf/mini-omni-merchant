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
    private final ToolOutcomeListener outcomeListener;
    private final GuardBlockedRecorder guardBlockedRecorder;

    private ToolCallbackScope(ToolCallback delegate, CallScope scope,
                              ToolTraceRecorder traceRecorder, ToolOutcomeListener outcomeListener,
                              GuardBlockedRecorder guardBlockedRecorder) {
        this.delegate = delegate;
        this.scope = scope;
        this.traceRecorder = traceRecorder;
        this.outcomeListener = outcomeListener;
        this.guardBlockedRecorder = guardBlockedRecorder;
    }

    /** 用给定作用域包装一个 ToolCallback（不补记轨迹）。 */
    public static ToolCallback wrap(ToolCallback delegate, CallScope scope) {
        return new ToolCallbackScope(delegate, scope, null, null, null);
    }

    /** 用给定作用域包装，并在需要时为未审计工具补记轨迹。 */
    public static ToolCallback wrap(ToolCallback delegate, CallScope scope, ToolTraceRecorder traceRecorder) {
        return new ToolCallbackScope(delegate, scope, traceRecorder, null, null);
    }

    /**
     * 用给定作用域包装，并同时挂载轨迹记录器与工具结果监听器。
     *
     * <p>结果监听器（供状态机使用）<b>每次真实工具执行恰好触发一次</b>，与轨迹记录的
     * 去重标记无关，避免"审计层触发一次、包装器又触发一次"。
     */
    public static ToolCallback wrap(ToolCallback delegate, CallScope scope,
                                    ToolTraceRecorder traceRecorder, ToolOutcomeListener outcomeListener) {
        return new ToolCallbackScope(delegate, scope, traceRecorder, outcomeListener, null);
    }

    /**
     * 完整包装：额外挂载「被 Guard 拦截」记录器（供记录 GUARD 轨迹步）。
     *
     * <p>注意包装顺序：调用方应让本 Scope 位于 {@code GuardedToolCallback} <b>外层</b>，
     * 这样 Guard 执行时线程上下文已绑定，且拦截结果能被本类观察到。
     */
    public static ToolCallback wrap(ToolCallback delegate, CallScope scope,
                                    ToolTraceRecorder traceRecorder, ToolOutcomeListener outcomeListener,
                                    GuardBlockedRecorder guardBlockedRecorder) {
        return new ToolCallbackScope(delegate, scope, traceRecorder, outcomeListener, guardBlockedRecorder);
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
        GuardOutcomeHolder.clear();
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
                var toolName = delegate.getToolDefinition().name();
                // 1. 守卫拦截检查：被拦截时既不能当成真实执行，也不能推进业务状态
                var guardOutcome = GuardOutcomeHolder.consume();
                if (guardOutcome != null) {
                    if (guardBlockedRecorder != null) {
                        guardBlockedRecorder.record(new GuardBlocked(
                                scope.traceId(), scope.conversationUuid(), toolName, callId,
                                guardOutcome.reason(), result, latencyMs));
                    }
                    // 只记录 GUARD 步；不记录 TOOL 步、不触发状态机（避免伪造成功）
                } else {
                    // 2. 审计层已记账则不重复；否则由本包装器补记（典型为 PolicyTools）
                    var alreadyRecorded = ToolTraceMarker.consume();
                    if (traceRecorder != null && alreadyRecorded == null) {
                        traceRecorder.record(new ToolTrace(
                                scope.traceId(), scope.conversationUuid(), toolName, callId,
                                failure == null, toolInput,
                                failure == null ? result : failure.getMessage(), latencyMs));
                    }
                    // 3. 工具结果监听器：真实执行恰好一次，通知状态机推进业务状态
                    if (outcomeListener != null && failure == null) {
                        final var finalOutput = result;
                        scope.runInScope(() -> {
                            outcomeListener.onToolCompleted(toolName, finalOutput);
                            return null;
                        });
                    }
                }
            } finally {
                ToolTraceMarker.clear();
                GuardOutcomeHolder.clear();
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

    /**
     * 工具结果监听器：工具成功返回后调用一次，供状态机推进业务状态。
     *
     * <p>只观察结果，<b>不重新执行工具</b>。
     */
    @FunctionalInterface
    public interface ToolOutcomeListener {
        void onToolCompleted(String toolName, String output);
    }

    /** 被 Guard 拦截的一次工具调用（用于记录 GUARD 轨迹步）。 */
    public record GuardBlocked(
            String traceId,
            String conversationUuid,
            String toolName,
            String toolCallId,
            String reason,
            String output,
            Integer latencyMs) {
    }

    /**
     * 守卫拦截记录器：工具被 Guard 拦截（重复 / 锁失效）时调用，用于记录 GUARD 步。
     *
     * <p>与 {@link ToolTraceRecorder} 区分：后者只记录<b>真实执行</b>的 TOOL 步。
     */
    @FunctionalInterface
    public interface GuardBlockedRecorder {
        void record(GuardBlocked guardBlocked);
    }
}
