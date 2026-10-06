package com.omnimerchant.agent.context;

/**
 * 当前 Agent 调用的线程上下文。
 * 携带意图（intent）与会话 UUID，供下游组件
 * （如 RateLimitedChatModel、TokenUsageAdvisor）附加更丰富的元数据。
 *
 * <p>复现自参考项目 {@code agent/context/CallContextHolder}。
 * 本阶段 Tool 层用它把 conversationUuid 带进 ToolAuditService 的审计日志。
 */
public final class CallContextHolder {

    private static final ThreadLocal<CallContext> CONTEXT = new ThreadLocal<>();

    private CallContextHolder() {}

    public static void set(String intent, String conversationUuid) {
        CONTEXT.set(new CallContext(intent, conversationUuid));
    }

    public static CallContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    /** 调用上下文内容：意图 + 会话 UUID。 */
    public record CallContext(String intent, String conversationUuid) {}
}
