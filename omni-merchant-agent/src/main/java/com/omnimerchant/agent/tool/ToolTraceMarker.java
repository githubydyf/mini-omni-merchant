package com.omnimerchant.agent.tool;

/**
 * 工具审计「已记账」标记（同一工具执行线程内有效）。
 *
 * <p>解决的问题（任务 §18）：同一次 Tool 调用只能对应一条逻辑上的 TOOL 轨迹，
 * 不能因为「ToolAuditService 记一次 + 回调包装器又记一次」而翻倍。
 *
 * <p>机制：使用 ToolAuditService 的工具（OrderTools / LogisticsTools /
 * ProductTools / EscalationTools）在成功写入 tool_call_log 后，会在**当前执行线程**
 * 打上标记；{@code ToolCallbackScope} 在调用返回后检查该标记：
 * <ul>
 *   <li>标记存在 → 说明审计层已产生 TOOL 步，包装器<b>不再</b>补记；</li>
 *   <li>标记缺失 → 说明该工具未走审计（典型是 knowledge 模块的 PolicyTools），
 *       由包装器补记一条真实 TOOL 步。</li>
 * </ul>
 *
 * <p>因为工具执行与审计写入发生在同一线程（都在 boundedElastic 工具线程上），
 * 用 ThreadLocal 传递是可靠的。
 */
public final class ToolTraceMarker {

    private static final ThreadLocal<String> LAST_RECORDED_TOOL_CALL_ID = new ThreadLocal<>();

    private ToolTraceMarker() {
    }

    /** 审计层成功记录一次工具调用后调用。 */
    public static void mark(String toolCallId) {
        LAST_RECORDED_TOOL_CALL_ID.set(toolCallId == null ? "" : toolCallId);
    }

    /**
     * 取出并清除标记。
     *
     * @return 已记录的工具调用 ID；未记录时返回 null
     */
    public static String consume() {
        var value = LAST_RECORDED_TOOL_CALL_ID.get();
        LAST_RECORDED_TOOL_CALL_ID.remove();
        return value;
    }

    /** 清理，防止线程池复用造成误判。 */
    public static void clear() {
        LAST_RECORDED_TOOL_CALL_ID.remove();
    }
}
