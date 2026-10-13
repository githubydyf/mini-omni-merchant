package com.omnimerchant.agent.service;

import com.omnimerchant.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;

import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * 失败归因：把异常 / 错误信息归类成稳定的失败分类。
 *
 * <p>复现自参考项目 {@code FailureAttributionService}，但做了当前阶段必要的裁剪：
 * 当前项目尚未引入 CircuitBreaker / Shopify / Webhook，因此<b>不引入 resilience4j</b>，
 * 只保留真正会用到的分类。
 *
 * <p>归类只来自真实异常类型、明确错误码或消息判定，不臆造。
 */
@Slf4j
@Service
public class FailureAttributionService {

    public static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
    /** 限流拒绝（QPS/预算/并发），属于业务拒绝而非模型故障。 */
    public static final String RATE_LIMITED = "RATE_LIMITED";
    /** 熔断器 OPEN，快速拒绝。 */
    public static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";
    public static final String LLM_TIMEOUT = "LLM_TIMEOUT";
    public static final String CLASSIFICATION_PARSE_ERROR = "CLASSIFICATION_PARSE_ERROR";
    public static final String MEMORY_UNAVAILABLE = "MEMORY_UNAVAILABLE";
    public static final String TOOL_EXCEPTION = "TOOL_EXCEPTION";
    public static final String RAG_NO_RESULT = "RAG_NO_RESULT";
    public static final String TENANT = "TENANT";
    public static final String AUTH = "AUTH";
    public static final String UNKNOWN = "UNKNOWN";

    /** 分类调用保留具体限流码，并识别被 Reactor 或阻塞等待包装的原始异常。 */
    public String classifyClassificationFailure(Throwable throwable, String fallbackCategory) {
        for (var cause = Exceptions.unwrap(throwable); cause != null; cause = cause.getCause()) {
            if (cause instanceof com.omnimerchant.agent.ratelimit.RateLimitedChatModel.RateLimitedException limited) {
                return limited.rejectReason() == null ? RATE_LIMITED : limited.rejectReason();
            }
            if (cause instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
                return CIRCUIT_OPEN;
            }
            if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
                return LLM_TIMEOUT;
            }
            if (cause instanceof tools.jackson.core.JacksonException) {
                return CLASSIFICATION_PARSE_ERROR;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return fallbackCategory;
    }

    /** 依据真实异常归类。 */
    public String classify(Throwable throwable) {
        if (throwable == null) {
            return UNKNOWN;
        }
        var unwrapped = Exceptions.unwrap(throwable);
        if (unwrapped instanceof com.omnimerchant.agent.ratelimit.RateLimitedChatModel.RateLimitedException) {
            return RATE_LIMITED;
        }
        if (unwrapped instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            return CIRCUIT_OPEN;
        }
        if (unwrapped instanceof TimeoutException || unwrapped instanceof HttpTimeoutException) {
            return LLM_TIMEOUT;
        }
        if (unwrapped instanceof BusinessException businessException) {
            return classifyErrorCode(businessException.getCode());
        }
        return classifyMessage(unwrapped.getMessage());
    }

    /** 依据错误信息 / 错误码文本归类（用于不抛异常、仅返回 error 事件的路径）。 */
    public String classifyMessage(String message) {
        if (message == null || message.isBlank()) {
            return UNKNOWN;
        }
        var normalized = message.toUpperCase(Locale.ROOT);
        for (var category : java.util.List.of("BUDGET_EXCEEDED", "QPS_LIMITED", "CONCURRENT_LIMITED",
                "RATE_LIMITER_UNAVAILABLE", CLASSIFICATION_PARSE_ERROR, MEMORY_UNAVAILABLE, CIRCUIT_OPEN,
                MODEL_UNAVAILABLE, LLM_TIMEOUT)) {
            if (normalized.contains(category)) {
                return category;
            }
        }
        if (normalized.contains("未配置") && normalized.contains("模型")) {
            return MODEL_UNAVAILABLE;
        }
        if (normalized.contains("401") || normalized.contains("UNAUTHORIZED")) {
            return AUTH;
        }
        if (normalized.contains("TENANT") || normalized.contains("租户")) {
            return TENANT;
        }
        if (normalized.contains("TIMEOUT") || normalized.contains("超时")) {
            return LLM_TIMEOUT;
        }
        if (normalized.contains("MODEL") && normalized.contains("CONFIG")) {
            return MODEL_UNAVAILABLE;
        }
        if (normalized.contains("TOOL") || normalized.contains("工具")) {
            return TOOL_EXCEPTION;
        }
        if (normalized.contains("RAG") && normalized.contains("NO")) {
            return RAG_NO_RESULT;
        }
        if (normalized.contains("工具缺失") || normalized.contains("ALLOWLIST")) {
            return TOOL_EXCEPTION;
        }
        return UNKNOWN;
    }

    private String classifyErrorCode(String code) {
        if (code == null) {
            return UNKNOWN;
        }
        if ("401".equals(code)) {
            return AUTH;
        }
        if ("403".equals(code) || code.startsWith("T")) {
            return TENANT;
        }
        if (code.startsWith("K")) {
            return RAG_NO_RESULT;
        }
        if ("A003".equals(code)) {
            return TOOL_EXCEPTION;
        }
        return UNKNOWN;
    }
}
