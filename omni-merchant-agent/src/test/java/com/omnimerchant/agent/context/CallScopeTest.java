package com.omnimerchant.agent.context;

import com.omnimerchant.tenant.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证请求作用域上下文的绑定与清理：
 * <ul>
 *   <li>作用域内可读到正确的 tenantId / intent / conversationUuid；</li>
 *   <li>作用域结束后恢复现场；</li>
 *   <li>连续两次作用域之间不串扰（模拟线程池复用线程）。</li>
 * </ul>
 */
class CallScopeTest {

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        CallContextHolder.clear();
    }

    @Test
    void shouldBindContextInsideScope() {
        var scope = new CallScope(1001L, "POLICY_QA", "conv-a");

        var observed = scope.runInScope(() -> {
            var call = CallContextHolder.get();
            return TenantContextHolder.get() + "|" + call.intent() + "|" + call.conversationUuid();
        });

        assertThat(observed).isEqualTo("1001|POLICY_QA|conv-a");
    }

    @Test
    void shouldNotLeakBetweenTwoScopesOnSameThread() {
        // 第一次请求（模拟线程池线程）
        new CallScope(1001L, "ORDER_STATUS", "conv-1")
                .runInScope(() -> CallContextHolder.get().conversationUuid());

        // 第二次请求复用同一线程：必须读到自己的上下文，而不是上一次的
        var second = new CallScope(2002L, "POLICY_QA", "conv-2")
                .runInScope(() -> {
                    var call = CallContextHolder.get();
                    return TenantContextHolder.get() + "|" + call.conversationUuid();
                });

        assertThat(second).isEqualTo("2002|conv-2");
    }

    @Test
    void shouldRestorePreviousContextAfterScope() {
        TenantContextHolder.set(999L);
        CallContextHolder.set("OLD", "old-conv");

        new CallScope(1001L, "POLICY_QA", "conv-a").runInScope(() -> "ignored");

        assertThat(TenantContextHolder.get()).isEqualTo(999L);
        assertThat(CallContextHolder.get().conversationUuid()).isEqualTo("old-conv");

        CallContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    void shouldClearContextWhenPreviouslyEmpty() {
        new CallScope(1001L, "POLICY_QA", "conv-a").runInScope(() -> "ignored");

        assertThat(TenantContextHolder.get()).isNull();
        assertThat(CallContextHolder.get()).isNull();
    }

    @Test
    void shouldClearEvenWhenActionThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new CallScope(1001L, "POLICY_QA", "conv-a")
                                .runInScope(() -> {
                                    throw new IllegalStateException("boom");
                                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(TenantContextHolder.get()).isNull();
        assertThat(CallContextHolder.get()).isNull();
    }
}
