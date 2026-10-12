package com.omnimerchant.agent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 用量采集（当前阶段为最简版）。
 *
 * <p>复现自参考项目 {@code advisor/TokenUsageAdvisor}，但<b>裁剪掉 RocketMQ 与
 * token_usage_daily 落库</b>（当前项目未复现消息队列与计费系统）：
 * <ul>
 *   <li>只保留进程内计数（用于日志与调试观察）；</li>
 *   <li>Token 数值必须来自模型真实返回的 Usage。</li>
 * </ul>
 *
 * <p><b>不伪造 Token</b>：拿不到真实 Usage 时调用方不得传入固定估值；
 * 本类的计数只反映真实采集到的数据。
 */
@Slf4j
@Component
public class TokenUsageAdvisor {

    private final AtomicLong totalPromptTokens = new AtomicLong(0);
    private final AtomicLong totalCompletionTokens = new AtomicLong(0);
    private final AtomicLong totalCalls = new AtomicLong(0);

    /**
     * 记录一次模型调用的真实 Token 用量。
     *
     * @param promptTokens     输入 token（来自真实 Usage；未知则为 null）
     * @param completionTokens 输出 token（来自真实 Usage；未知则为 null）
     * @param model            模型名（真实配置）
     * @param latencyMs        本次调用耗时
     */
    public void record(Integer promptTokens, Integer completionTokens, String model, long latencyMs) {
        totalCalls.incrementAndGet();
        var prompt = promptTokens == null ? 0 : promptTokens;
        var completion = completionTokens == null ? 0 : completionTokens;
        totalPromptTokens.addAndGet(prompt);
        totalCompletionTokens.addAndGet(completion);
        log.info("Token 用量：model={}, promptTokens={}, completionTokens={}, total={}, latencyMs={}",
                model, promptTokens, completionTokens,
                (promptTokens == null && completionTokens == null) ? null : prompt + completion,
                latencyMs);
    }

    public long getTotalPromptTokens() {
        return totalPromptTokens.get();
    }

    public long getTotalCompletionTokens() {
        return totalCompletionTokens.get();
    }

    public long getTotalCalls() {
        return totalCalls.get();
    }
}
