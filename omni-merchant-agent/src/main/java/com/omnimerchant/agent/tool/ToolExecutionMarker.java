package com.omnimerchant.agent.tool;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 请求级「是否已执行过工具」标记。
 *
 * <p>用途：为「安全重试」提供判据（任务 §31/§34）。
 * 即使前端还没收到任何文本增量，模型也可能已经完成了 Tool Calling ——
 * 因此不能只看"有没有输出内容"，必须确认"有没有执行过工具"。
 *
 * <p><b>为什么用 AtomicBoolean 而不是 ThreadLocal</b>：工具在 Reactor 的
 * boundedElastic 线程执行，而重试判据在模型流管道线程上读取，ThreadLocal 不跨线程。
 * 用 AtomicBoolean 保证跨线程可见。
 *
 * <p><b>误差方向是安全的</b>：该标记在当前进程内共享（非严格按请求隔离），
 * 并发请求可能让本值为 true，从而让重试判据更保守（拒绝重试）。
 * 保守方向只会"少重试"，绝不会导致"重复执行副作用工具"。
 * 因此主链默认关闭自动重试（{@code app.llm.resilience.max-retries: 0}），
 * 本标记用于在开启重试时兜底。
 */
public final class ToolExecutionMarker {

    private static final AtomicBoolean EXECUTED = new AtomicBoolean(false);

    private ToolExecutionMarker() {
    }

    /** 标记已执行过工具。 */
    public static void markExecuted() {
        EXECUTED.set(true);
    }

    /** 是否已执行过工具。 */
    public static boolean executed() {
        return EXECUTED.get();
    }

    /** 清理（新一轮请求开始前调用）。 */
    public static void clear() {
        EXECUTED.set(false);
    }
}
