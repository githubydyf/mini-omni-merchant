package com.omnimerchant.agent.context;

/**
 * 当前 AgentRun 的 traceId 线程上下文。
 *
 * <p>存在的必要性：Spring AI 的工具回调在 Reactor 的 boundedElastic 线程执行，
 * 而 MDC 是 ThreadLocal，不跨线程传播。若只依赖 MDC，工具线程会拿不到本轮 traceId，
 * 退化成随机 UUID，导致 {@code agent_run.trace_id} 与 {@code tool_call_log.trace_id}
 * 不一致。
 *
 * <p>因此把 traceId 放入显式的线程上下文，由 {@code CallScope} 在工具真正执行的线程上
 * 绑定与恢复，{@code ToolAuditService} 优先从这里读取。
 */
public final class TraceContextHolder {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceContextHolder() {
    }

    public static void set(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String get() {
        return TRACE_ID.get();
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
