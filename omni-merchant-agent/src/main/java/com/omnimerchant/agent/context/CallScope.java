package com.omnimerchant.agent.context;

import com.omnimerchant.agent.context.CallContextHolder;
import com.omnimerchant.tenant.context.TenantContextHolder;

/**
 * 请求作用域上下文快照。
 *
 * <p>用于把一次请求的 tenantId / intent / conversationUuid 显式绑定到
 * <b>真正执行 Tool 的线程</b>上，并在执行结束后清理。
 */
public final class CallScope {

    private final Long tenantId;
    private final String intent;
    private final String conversationUuid;

    public CallScope(Long tenantId, String intent, String conversationUuid) {
        this.tenantId = tenantId;
        this.intent = intent;
        this.conversationUuid = conversationUuid;
    }

    public Long tenantId() {
        return tenantId;
    }

    public String intent() {
        return intent;
    }

    public String conversationUuid() {
        return conversationUuid;
    }

    /** 在当前线程绑定上下文。 */
    public void bind() {
        TenantContextHolder.set(tenantId);
        CallContextHolder.set(intent, conversationUuid);
    }

    /** 在当前线程清理上下文。 */
    public void clear() {
        TenantContextHolder.clear();
        CallContextHolder.clear();
    }

    /**
     * 在当前线程绑定上下文执行 action，无论成败都恢复现场。
     *
     * <p>恢复（而不是直接 clear）是关键：SSE 场景下工具线程由 Reactor 线程池复用，
     * 直接 clear 会破坏该线程上可能存在的其他上下文；恢复为"进入前的值"
     * 才能保证连续请求之间不串扰。
     */
    public <T> T runInScope(java.util.function.Supplier<T> action) {
        var previousTenant = TenantContextHolder.get();
        var previousCall = CallContextHolder.get();
        bind();
        try {
            return action.get();
        } finally {
            restore(previousTenant, previousCall);
        }
    }

    private void restore(Long previousTenant, CallContextHolder.CallContext previousCall) {
        if (previousTenant == null) {
            TenantContextHolder.clear();
        } else {
            TenantContextHolder.set(previousTenant);
        }
        if (previousCall == null) {
            CallContextHolder.clear();
        } else {
            CallContextHolder.set(previousCall.intent(), previousCall.conversationUuid());
        }
    }
}
