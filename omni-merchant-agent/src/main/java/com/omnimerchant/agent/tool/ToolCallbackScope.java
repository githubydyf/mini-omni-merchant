package com.omnimerchant.agent.tool;

import com.omnimerchant.agent.context.CallScope;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 把 Tool 执行绑定到正确的请求上下文。
 *
 * <p>为什么必须这么做：Spring AI 的 {@code ToolCallingAdvisor} 使用
 * {@code subscribeOn(Schedulers.boundedElastic())} 执行工具调用，工具实际运行在
 * Reactor 的 boundedElastic 线程上，<b>普通 ThreadLocal 不会自动传播</b>。
 * 若不处理，ToolAuditService 将拿不到 conversationUuid，也无法记录 tenantId。
 *
 * <p>这里不去依赖 Reactor 的上下文传播机制，而是直接把"当前请求的上下文"
 * 闭包捕获进回调，在工具真正执行的那一刻绑定到执行线程，执行完恢复现场。
 * 这样无论 Reactor 把任务调度到哪个线程，工具都能读到正确上下文。
 */
public final class ToolCallbackScope {

    private ToolCallbackScope() {
    }

    /** 用给定作用域包装一个 ToolCallback。 */
    public static ToolCallback wrap(ToolCallback delegate, CallScope scope) {
        return new ScopedToolCallback(delegate, scope);
    }

    /**
     * 带作用域的 ToolCallback 装饰器。
     */
    private record ScopedToolCallback(ToolCallback delegate, CallScope scope) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            // 绑定到实际执行线程，执行完恢复现场，避免连续请求上下文串扰
            return scope.runInScope(() -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return scope.runInScope(() -> delegate.call(toolInput, toolContext));
        }
    }
}
