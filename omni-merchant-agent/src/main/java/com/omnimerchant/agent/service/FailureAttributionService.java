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
    public static final String LLM_TIMEOUT = "LLM_TIMEOUT";
    public static final String TOOL_EXCEPTION = "TOOL_EXCEPTION";
    public static final String RAG_NO_RESULT = "RAG_NO_RESULT";
    public static final String TENANT = "TENANT";
    public static final String AUTH = "AUTH";
    public static final String UNKNOWN = "UNKNOWN";

    /** 依据真实异常归类。 */
    public String classify(Throwable throwable) {
        if (throwable == null) {
            return UNKNOWN;
        }
        var unwrapped = Exceptions.unwrap(throwable);
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
