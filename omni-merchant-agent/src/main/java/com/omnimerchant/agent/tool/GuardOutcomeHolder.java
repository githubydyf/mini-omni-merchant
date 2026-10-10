package com.omnimerchant.agent.tool;

/**
 * 工具守卫结果标记（同一工具执行线程内有效）。
 *
 * <p>解决的问题：副作用工具被 Guard 拦截时（{@code DUPLICATE_BLOCKED} / {@code LOCK_LOST}）
 * 返回的是一段<b>结构化 JSON 而不是异常</b>，外层 {@code ToolCallbackScope} 无法仅凭
 * "没有抛异常"判断是否真的执行了业务。
 *
 * <p>因此 Guard 在拦截时打上标记，Scope 读取后：
 * <ul>
 *   <li><b>不</b>触发状态机的业务成功推进（避免把重复拦截解释成新的申请成功）；</li>
 *   <li>记录一条 {@code GUARD} 轨迹步骤（BLOCKED），而不是伪造成功的 TOOL 步。</li>
 * </ul>
 *
 * <p>因为 Guard 与 Scope 的调用发生在同一工具执行线程，用 ThreadLocal 传递是可靠的。
 */
public final class GuardOutcomeHolder {

    private static final ThreadLocal<Outcome> OUTCOME = new ThreadLocal<>();

    private GuardOutcomeHolder() {
    }

    /** 标记本次工具调用被 Guard 拦截。 */
    public static void mark(String reason, String toolName) {
        OUTCOME.set(new Outcome(reason, toolName));
    }

    /** 取出并清除标记；未被拦截时返回 null。 */
    public static Outcome consume() {
        var outcome = OUTCOME.get();
        OUTCOME.remove();
        return outcome;
    }

    /** 清理，防止线程池复用造成误判。 */
    public static void clear() {
        OUTCOME.remove();
    }

    /**
     * 拦截结果。
     *
     * @param reason   拦截原因：DUPLICATE_BLOCKED / LOCK_LOST
     * @param toolName 被拦截的工具名
     */
    public record Outcome(String reason, String toolName) {
    }
}
