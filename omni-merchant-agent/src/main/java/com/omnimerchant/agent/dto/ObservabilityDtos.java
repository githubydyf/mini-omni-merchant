package com.omnimerchant.agent.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 可观测性（Trace 轨迹）返回 DTO。
 *
 * <p>复现自参考项目 {@code dto/ObservabilityDtos}，但本阶段<b>只保留 Trace 相关三个 VO</b>：
 * {@link TraceSummaryVO} / {@link TraceStepVO} / {@link TraceDetailVO}。
 * 其余统计类 VO（ObservabilitySummaryVO / EvalTrendVO / SRE 等）依赖尚未复现的模块，
 * 本阶段不实现。
 *
 * <p>字段与 {@code omnimerchant-web} 的 {@code TracesView.vue} 消费保持一致，前端无需改动。
 */
public final class ObservabilityDtos {

    private ObservabilityDtos() {
    }

    /** 轨迹列表项 / 详情中的 run 概要。 */
    public record TraceSummaryVO(
            String traceId,
            String conversationUuid,
            String runType,
            String intent,
            String modelName,
            String status,
            String failureCategory,
            Integer toolCallCount,
            Integer citationCount,
            Integer firstTokenLatencyMs,
            Integer totalLatencyMs,
            BigDecimal costUsd,
            LocalDateTime startedAt,
            LocalDateTime finishedAt) {
    }

    /** 轨迹步骤。 */
    public record TraceStepVO(
            Integer stepIndex,
            String stepType,
            String name,
            String status,
            String inputSummary,
            String outputSummary,
            String toolCallId,
            Integer latencyMs,
            String failureCategory,
            String failureReason,
            String metadataJson,
            LocalDateTime createdAt) {
    }

    /** 轨迹详情：run + 按 stepIndex 升序的 steps。 */
    public record TraceDetailVO(
            TraceSummaryVO run,
            List<TraceStepVO> steps) {
    }
}
