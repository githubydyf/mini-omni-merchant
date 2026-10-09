package com.omnimerchant.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.context.CallContextHolder;
import com.omnimerchant.agent.context.TraceContextHolder;
import com.omnimerchant.agent.entity.ToolCallLog;
import com.omnimerchant.agent.mapper.ToolCallLogMapper;
import com.omnimerchant.agent.tool.ToolTraceMarker;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tool 调用审计：把每次 Tool 调用记录进 {@code tool_call_log}。
 *
 * <p>复现自参考项目 {@code ToolAuditService}：计时 → 执行 supplier →
 * 成功存 result/success=1，失败记 errorCode=TOOL_EXCEPTION 并重新抛出 →
 * 写日志（toolVersion=v1、params JSON、paramsHash=MD5、latencyMs、
 * cacheHit/retryCount/isRetry=0）。
 *
 * <p><b>审计失败不影响业务</b>：写日志抛错只 warn，原 Tool 结果/异常照常返回。
 *
 * <p>最小适配：
 * <ul>
 *   <li>已恢复对 {@code agentTraceService.recordToolStep(log)} 的调用（阶段 5）：
 *       每次成功写入 tool_call_log 后同步产生一条 TOOL 轨迹。</li>
 *   <li>tenantId 取自 {@link TenantContextHolder}；当前未启用多租户拦截，通常为 null。</li>
 *   <li>conversationUuid 取自 {@link CallContextHolder}，无上下文时按原项目回退为 {@code "unknown"}。</li>
 *   <li>traceId 优先取自 {@link TraceContextHolder}（工具线程上由 CallScope 绑定），
 *       其次 MDC，最后才回退随机 UUID。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolAuditService {

    private final ToolCallLogMapper mapper;
    private final ObjectMapper objectMapper;
    private final AgentTraceService agentTraceService;

    public <T> T record(String toolName, Map<String, Object> params, Supplier<T> supplier) {
        var started = LocalDateTime.now();
        var callId = UUID.randomUUID().toString();
        try {
            var result = supplier.get();
            insert(toolName, params, result, null, null, started, callId);
            return result;
        } catch (RuntimeException e) {
            insert(toolName, params, null, "TOOL_EXCEPTION", e.getMessage(), started, callId);
            throw e;
        }
    }

    private void insert(String toolName, Map<String, Object> params, Object result,
                        String errorCode, String errorMessage, LocalDateTime started,
                        String callId) {
        if (mapper == null) {
            return;
        }
        try {
            var ended = LocalDateTime.now();
            var paramsJson = toJson(params);
            var resultJson = result == null ? null : toJson(result);
            var callContext = CallContextHolder.get();
            var log = new ToolCallLog();
            log.setTraceId(resolveTraceId());
            log.setTenantId(TenantContextHolder.get());
            log.setConversationUuid(callContext != null ? callContext.conversationUuid() : "unknown");
            log.setToolCallId(callId);
            log.setToolName(toolName);
            log.setToolVersion("v1");
            log.setParams(paramsJson);
            log.setParamsHash(md5(paramsJson));
            log.setSuccess(errorCode == null ? 1 : 0);
            log.setResult(resultJson);
            log.setResultSizeBytes(resultJson == null ? 0 : resultJson.getBytes(StandardCharsets.UTF_8).length);
            log.setErrorCode(errorCode);
            log.setErrorMessage(errorMessage);
            log.setStartedAt(started);
            log.setEndedAt(ended);
            log.setLatencyMs((int) Duration.between(started, ended).toMillis());
            log.setCacheHit(0);
            log.setRetryCount(0);
            log.setIsRetry(0);
            mapper.insert(log);
            // 阶段 5：工具调用落库后，同步产生一条真实 TOOL 轨迹（bestEffort，失败不影响业务）
            agentTraceService.recordToolStep(log);
            // 打标记：告诉 ToolCallbackScope 本次调用已由审计层记账，避免重复记录 TOOL 步
            ToolTraceMarker.mark(log.getToolCallId());
        } catch (Exception e) {
            log.warn("写入工具审计日志失败 {}：{}", toolName, e.getMessage());
        }
    }

    /**
     * traceId 解析顺序：工具线程上下文 → MDC → 随机 UUID。
     *
     * <p>工具在 Reactor boundedElastic 线程执行，MDC 不跨线程，因此优先读
     * {@link TraceContextHolder}（由 CallScope 在工具线程上绑定本轮 AgentRun 的 traceId）。
     */
    private String resolveTraceId() {
        var traceId = TraceContextHolder.get();
        if (traceId == null || traceId.isBlank()) {
            traceId = MDC.get("traceId");
        }
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }

    private String toJson(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    private String md5(String value) throws Exception {
        if (value == null) {
            return null;
        }
        var digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
