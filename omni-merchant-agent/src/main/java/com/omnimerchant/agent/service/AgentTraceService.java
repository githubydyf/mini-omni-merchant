package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.dto.ObservabilityDtos;
import com.omnimerchant.agent.entity.AgentRun;
import com.omnimerchant.agent.entity.AgentStep;
import com.omnimerchant.agent.entity.ToolCallLog;
import com.omnimerchant.agent.mapper.AgentRunMapper;
import com.omnimerchant.agent.mapper.AgentStepMapper;
import com.omnimerchant.agent.mapper.ToolCallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 调用轨迹服务。
 *
 * <p>复现自参考项目 {@code service/AgentTraceService}，保留其核心语义：
 * <ul>
 *   <li>{@code startChatRun} 创建 RUNNING 的 AgentRun，返回唯一 traceId；</li>
 *   <li>{@code addStep} 记录 ROUTER / MEMORY / TOOL / FINAL_ANSWER / FAILURE 步骤；</li>
 *   <li>{@code recordToolStep} 由 {@code ToolAuditService} 回调，把工具调用落成 TOOL 步；</li>
 *   <li>{@code completeRun} / {@code failRun} 收尾；</li>
 *   <li>{@code listTraces} / {@code getTrace} 供查询接口使用。</li>
 * </ul>
 *
 * <p><b>bestEffort</b>：所有写入失败只记 warning，绝不把已经正常完成/正常失败的
 * 业务链路改变结果，也绝不重新执行任何工具（任务 §26）。
 *
 * <p><b>不伪造指标</b>：Token / 成本 / 引用数当前无采集能力，保持 0 或 null。
 *
 * <p><b>脱敏</b>：用户输入、工具摘要、异常信息落库前都会脱敏（任务 §25），
 * 且 hash 不能代替脱敏，因此两者都做。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTraceService {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private final AgentRunMapper runMapper;
    private final AgentStepMapper stepMapper;
    private final ToolCallLogMapper toolCallLogMapper;
    private final FailureAttributionService failureAttributionService;
    private final ObjectMapper objectMapper;

    // ==================================================================
    // 生命周期
    // ==================================================================

    /**
     * 开始一次正式 Chat 运行。
     *
     * <p>每个请求生成<b>唯一</b> traceId，不使用 conversationUuid 代替
     * （同一会话多轮必须是不同 Run）。
     *
     * @return traceId；Trace 写入失败时仍返回生成的 traceId，业务可继续
     */
    public String startChatRun(Long tenantId, String conversationUuid, String intent,
                               String modelProvider, String modelName, String userMessage) {
        var traceId = newTraceId();
        bestEffort(() -> {
            var now = LocalDateTime.now();
            var run = new AgentRun();
            run.setTenantId(tenantId);
            run.setTraceId(traceId);
            run.setConversationUuid(conversationUuid);
            run.setRunType("CHAT");
            run.setIntent(intent);
            run.setModelProvider(modelProvider);
            run.setModelName(modelName);
            run.setRouterDecision(modelName);
            run.setInputRedacted(redact(userMessage));
            run.setInputHash(sha256(userMessage));
            run.setStatus(STATUS_RUNNING);
            run.setPromptTokens(0L);
            run.setCompletionTokens(0L);
            run.setCostUsd(BigDecimal.ZERO);
            run.setToolCallCount(0);
            run.setRetrievedDocCount(0);
            run.setCitationCount(0);
            run.setStartedAt(now);
            runMapper.insert(run);
        }, "startChatRun");
        return traceId;
    }

    /**
     * 追加一个轨迹步骤。
     *
     * <p>stepIndex 由「锁定 agent_run 行 + 统计已有步骤数」在短事务内分配，
     * 保证并发记录 TOOL 步时序号不重复、顺序可靠（任务 §13）。
     */
    @Transactional
    public void addStep(String traceId, String stepType, String name, String status,
                        String inputSummary, String outputSummary, String toolCallId,
                        Integer latencyMs, Map<String, Object> metadata) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        try {
            // 锁定该 Run 行：串行化同一 Run 内的 stepIndex 分配
            var run = runMapper.selectOne(new LambdaQueryWrapper<AgentRun>()
                    .eq(AgentRun::getTraceId, traceId)
                    .last("FOR UPDATE"));
            if (run == null) {
                log.warn("轨迹步骤被跳过：找不到 traceId={} 对应的 AgentRun", traceId);
                return;
            }
            addStepInternal(traceId, run, stepType, name, status, inputSummary, outputSummary,
                    toolCallId, latencyMs, null, metadata);
        } catch (Exception e) {
            log.warn("轨迹步骤写入失败（不影响业务）：traceId={}, type={}, error={}",
                    traceId, stepType, e.getMessage());
        }
    }

    /**
     * 落库一条步骤（调用方必须已持有 agent_run 行锁）。
     *
     * @param explicitFailureCategory 显式失败分类；为 null 时按 outputSummary 推断
     */
    private void addStepInternal(String traceId, AgentRun run, String stepType, String name,
                                 String status, String inputSummary, String outputSummary,
                                 String toolCallId, Integer latencyMs,
                                 String explicitFailureCategory, Map<String, Object> metadata) {
        var count = stepMapper.selectCount(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getTraceId, traceId));

        var step = new AgentStep();
        step.setTenantId(run.getTenantId());
        step.setAgentRunId(run.getId());
        step.setTraceId(traceId);
        step.setStepIndex(count.intValue() + 1);
        step.setStepType(stepType);
        step.setName(name);
        var normalizedStatus = status == null ? STATUS_SUCCESS : status;
        step.setStatus(normalizedStatus);
        // 摘要统一脱敏 + 截断
        var redactedOutput = concise(redact(outputSummary), 2048);
        step.setInputSummary(concise(redact(inputSummary), 2048));
        step.setOutputSummary(redactedOutput);
        step.setToolCallId(toolCallId);
        step.setLatencyMs(latencyMs);
        if (!STATUS_SUCCESS.equals(normalizedStatus)) {
            var category = explicitFailureCategory != null && !explicitFailureCategory.isBlank()
                    ? explicitFailureCategory
                    : failureAttributionService.classifyMessage(redactedOutput);
            step.setFailureCategory(category);
            step.setFailureReason(concise(redact(outputSummary), 1024));
        }
        step.setMetadataJson(toJson(metadata));
        var endedAt = LocalDateTime.now();
        step.setEndedAt(endedAt);
        step.setStartedAt(endedAt.minus(Duration.ofMillis(latencyMs == null ? 0L : latencyMs.longValue())));
        stepMapper.insert(step);
    }

    /**
     * 由 {@code ToolAuditService} 回调：把一次真实工具调用记录成 TOOL 步。
     */
    public void recordToolStep(ToolCallLog toolCallLog) {
        if (toolCallLog == null || toolCallLog.getTraceId() == null) {
            return;
        }
        var success = Integer.valueOf(1).equals(toolCallLog.getSuccess());
        addStep(toolCallLog.getTraceId(), "TOOL", toolCallLog.getToolName(),
                success ? STATUS_SUCCESS : STATUS_FAILED,
                toolCallLog.getParams(),
                success ? toolCallLog.getResult() : toolCallLog.getErrorMessage(),
                toolCallLog.getToolCallId(),
                toolCallLog.getLatencyMs(),
                Map.of("errorCode", toolCallLog.getErrorCode() == null ? "" : toolCallLog.getErrorCode()));
    }

    /**
     * 记录一条由 {@code ToolCallbackScope} 补记的工具轨迹（用于未走审计的工具，如 PolicyTools）。
     *
     * <p>为避免与审计层重复，按 toolCallId 去重：同一 toolCallId 已存在 TOOL 步则跳过。
     */
    public void recordBackfilledToolStep(com.omnimerchant.agent.tool.ToolCallbackScope.ToolTrace toolTrace) {
        if (toolTrace == null || toolTrace.traceId() == null || toolTrace.traceId().isBlank()) {
            return;
        }
        try {
            var existing = stepMapper.selectCount(new LambdaQueryWrapper<AgentStep>()
                    .eq(AgentStep::getTraceId, toolTrace.traceId())
                    .eq(AgentStep::getToolCallId, toolTrace.toolCallId()));
            if (existing != null && existing > 0) {
                return;
            }
        } catch (Exception e) {
            log.warn("查询工具轨迹去重失败，跳过补记：{}", e.getMessage());
            return;
        }
        addStep(toolTrace.traceId(), "TOOL", toolTrace.toolName(),
                toolTrace.success() ? STATUS_SUCCESS : STATUS_FAILED,
                toolTrace.input(), toolTrace.output(), toolTrace.toolCallId(),
                toolTrace.latencyMs(), Map.of("source", "toolCallbackScope"));
    }

    /**
     * 一次运行成功收尾：更新 RUNNING → SUCCESS，并记录 FINAL_ANSWER 步。
     *
     * <p>toolCallCount 从真实 {@code tool_call_log} 统计，不臆造。
     */
    @Transactional
    public void completeRun(String traceId, String finalAnswer,
                            Integer firstTokenLatencyMs, Integer totalLatencyMs) {
        try {
            var run = findRunForUpdate(traceId);
            if (run == null) {
                return;
            }
            var toolCallCount = toolCallLogMapper.selectCount(new LambdaQueryWrapper<ToolCallLog>()
                    .eq(ToolCallLog::getTraceId, traceId)).intValue();
            runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                    .eq(AgentRun::getTraceId, traceId)
                    .set(AgentRun::getStatus, STATUS_SUCCESS)
                    .set(AgentRun::getFinalAnswerRedacted, concise(redact(finalAnswer), 4096))
                    .set(AgentRun::getFirstTokenLatencyMs, firstTokenLatencyMs)
                    .set(AgentRun::getTotalLatencyMs, totalLatencyMs)
                    .set(AgentRun::getToolCallCount, toolCallCount)
                    .set(AgentRun::getFinishedAt, LocalDateTime.now()));
            addStep(traceId, "FINAL_ANSWER", "assistant_response", STATUS_SUCCESS,
                    null, finalAnswer, null, totalLatencyMs, Map.of());
        } catch (Exception e) {
            log.warn("轨迹收尾失败（不影响业务）：traceId={}, error={}", traceId, e.getMessage());
        }
    }

    /**
     * 一次运行失败收尾：更新 → FAILED，并记录 FAILURE 步。
     */
    @Transactional
    public void failRun(String traceId, Throwable error, Integer totalLatencyMs) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        try {
            var run = findRunForUpdate(traceId);
            if (run == null) {
                return;
            }
            var category = failureAttributionService.classify(error);
            var reason = concise(redact(error == null ? null : error.getMessage()), 1024);
            runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                    .eq(AgentRun::getTraceId, traceId)
                    .set(AgentRun::getStatus, STATUS_FAILED)
                    .set(AgentRun::getFailureCategory, category)
                    .set(AgentRun::getFailureReason, reason)
                    .set(AgentRun::getTotalLatencyMs, totalLatencyMs)
                    .set(AgentRun::getFinishedAt, LocalDateTime.now()));
            addStepInternal(traceId, run, "FAILURE", "failure_attribution", STATUS_FAILED,
                    null, reason, null, totalLatencyMs, category, Map.of("category", category));
        } catch (Exception e) {
            log.warn("轨迹失败收尾写入失败：traceId={}, error={}", traceId, e.getMessage());
        }
    }

    /**
     * 用「分类 + 原因」显式标记失败（用于不抛异常、只返回 error 事件的路径）。
     *
     * <p>分类未显式给出时，由真实错误信息推断；Run 与 FAILURE 步使用同一分类，保持一致。
     */
    public void failRunWithReason(String traceId, String failureCategory, String reason,
                                  Integer totalLatencyMs) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        try {
            var run = findRunForUpdate(traceId);
            if (run == null) {
                return;
            }
            var redactedReason = concise(redact(reason), 1024);
            var category = failureCategory != null && !failureCategory.isBlank()
                    ? failureCategory
                    : failureAttributionService.classifyMessage(redactedReason);
            runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                    .eq(AgentRun::getTraceId, traceId)
                    .set(AgentRun::getStatus, STATUS_FAILED)
                    .set(AgentRun::getFailureCategory, category)
                    .set(AgentRun::getFailureReason, redactedReason)
                    .set(AgentRun::getTotalLatencyMs, totalLatencyMs)
                    .set(AgentRun::getFinishedAt, LocalDateTime.now()));
            // Run 与 FAILURE 步共用同一失败分类，避免两处不一致
            addStepInternal(traceId, run, "FAILURE", "failure_attribution", STATUS_FAILED,
                    null, redactedReason, null, totalLatencyMs, category,
                    Map.of("category", category));
        } catch (Exception e) {
            log.warn("轨迹失败收尾写入失败：traceId={}, error={}", traceId, e.getMessage());
        }
    }

    /**
     * 兜底收尾：仅当 Run 仍处于 RUNNING 时才标记失败
     * （用于客户端取消 / 超时，不能覆盖已成功的 Run）。
     */
    public void failRunIfStillRunning(String traceId, String failureCategory,
                                      String reason, Integer totalLatencyMs) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        try {
            var run = findRun(traceId);
            if (run == null || !STATUS_RUNNING.equals(run.getStatus())) {
                return;
            }
            failRunWithReason(traceId, failureCategory, reason, totalLatencyMs);
        } catch (Exception e) {
            log.warn("轨迹兜底收尾失败：traceId={}, error={}", traceId, e.getMessage());
        }
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 分页查询轨迹列表。
     *
     * @param tenantId 租户过滤（可空）；开发期由调用方传入，非生产级鉴权
     */
    public IPage<ObservabilityDtos.TraceSummaryVO> listTraces(Long tenantId, String conversationUuid,
                                                             String status, int page, int size) {
        var wrapper = new LambdaQueryWrapper<AgentRun>()
                .eq(tenantId != null, AgentRun::getTenantId, tenantId)
                .eq(conversationUuid != null && !conversationUuid.isBlank(),
                        AgentRun::getConversationUuid, conversationUuid)
                .eq(status != null && !status.isBlank(), AgentRun::getStatus, status)
                .orderByDesc(AgentRun::getStartedAt);
        return runMapper.selectPage(new Page<>(Math.max(1, page), clamp(size)), wrapper)
                .convert(this::toSummary);
    }

    /** 查询轨迹详情：run + 按 stepIndex 升序的 steps。未找到返回 null。 */
    public ObservabilityDtos.TraceDetailVO getTrace(String traceId) {
        var run = findRun(traceId);
        if (run == null) {
            return null;
        }
        var steps = stepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                        .eq(AgentStep::getTraceId, traceId)
                        .orderByAsc(AgentStep::getStepIndex))
                .stream().map(this::toStep).toList();
        return new ObservabilityDtos.TraceDetailVO(toSummary(run), steps);
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    private AgentRun findRun(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        return runMapper.selectOne(new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getTraceId, traceId)
                .last("LIMIT 1"));
    }

    /** 带行锁的查找：收尾阶段分配 stepIndex 时必须串行化。 */
    private AgentRun findRunForUpdate(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        return runMapper.selectOne(new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getTraceId, traceId)
                .last("FOR UPDATE"));
    }

    private ObservabilityDtos.TraceSummaryVO toSummary(AgentRun run) {
        return new ObservabilityDtos.TraceSummaryVO(
                run.getTraceId(), run.getConversationUuid(), run.getRunType(), run.getIntent(),
                run.getModelName(), run.getStatus(), run.getFailureCategory(), run.getToolCallCount(),
                run.getCitationCount(), run.getFirstTokenLatencyMs(), run.getTotalLatencyMs(),
                run.getCostUsd(), run.getStartedAt(), run.getFinishedAt());
    }

    private ObservabilityDtos.TraceStepVO toStep(AgentStep step) {
        return new ObservabilityDtos.TraceStepVO(
                step.getStepIndex(), step.getStepType(), step.getName(), step.getStatus(),
                step.getInputSummary(), step.getOutputSummary(), step.getToolCallId(),
                step.getLatencyMs(), step.getFailureCategory(), step.getFailureReason(),
                step.getMetadataJson(), step.getEndedAt());
    }

    /** 每个 Run 独立唯一 traceId（去掉连字符，64 位以内）。 */
    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 基础脱敏：邮箱 / 手机号 / 银行卡号 / 常见密钥形态。
     * hash 不能代替脱敏，所以调用方同时保存 hash。
     */
    private String redact(String value) {
        if (value == null) {
            return null;
        }
        return value
                // API Key / Bearer Token
                .replaceAll("(?i)(sk-[A-Za-z0-9_.-]{8,})", "[apikey]")
                .replaceAll("(?i)(bearer)\\s+[A-Za-z0-9._-]{8,}", "$1 [token]")
                // 邮箱
                .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[email]")
                // 银行卡号（13~19 位连续数字）
                .replaceAll("\\b(?:\\d[ -]*?){13,19}\\b", "[card]")
                // 手机号（+ 或 1 开头的 11 位或更长）
                .replaceAll("\\+?\\d[\\d\\s().-]{7,}\\b", "[phone]");
    }

    private String sha256(String value) {
        if (value == null) {
            return null;
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            log.warn("计算 inputHash 失败：{}", e.getMessage());
            return null;
        }
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return metadata == null || metadata.isEmpty() ? "{}" : objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String concise(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private int clamp(int size) {
        return Math.max(1, Math.min(size, 100));
    }

    /** 轨迹写入一律尽力而为：失败只告警，不影响业务链路。 */
    private void bestEffort(CheckedRunnable runnable, String action) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.warn("Agent 轨迹写入跳过（{}）：{}", action, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
