package com.omnimerchant.agent.ratelimit;

/**
 * 限流检查结果（复现自参考项目 {@code ratelimit/RateLimitResult}）。
 *
 * <p>{@code rejectReason} 取值：
 * <ul>
 *   <li>{@code QPS_LIMITED} —— 单位时间请求数超限；</li>
 *   <li>{@code BUDGET_EXCEEDED} —— 月度 Token 预算不足；</li>
 *   <li>{@code CONCURRENT_LIMITED} —— 同租户并发模型调用数超限；</li>
 *   <li>{@code MISSING_TENANT} —— 缺少租户上下文；</li>
 *   <li>{@code RATE_LIMITER_UNAVAILABLE} —— Redis 不可用（fail-closed）。</li>
 * </ul>
 *
 * <p>注意：限流拒绝<b>不是</b>模型服务故障，不得计入熔断器失败统计。
 */
public record RateLimitResult(boolean allowed, String rejectReason) {

    public static RateLimitResult ok() {
        return new RateLimitResult(true, null);
    }

    public static RateLimitResult reject(String reason) {
        return new RateLimitResult(false, reason);
    }
}
