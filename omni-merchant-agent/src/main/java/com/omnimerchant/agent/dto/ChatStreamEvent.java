package com.omnimerchant.agent.dto;

/**
 * SSE 流式事件。
 *
 * <p>复现自参考项目 {@code dto/ChatStreamEvent}。{@code type} 即 SSE 的 event 名称，
 * 必须保持原定义，不得改名：
 * <ul>
 *   <li>{@code status} —— 执行状态（例如 PROCESSING）</li>
 *   <li>{@code translated_delta} —— 内容增量（本阶段无多语言，直接承载模型输出增量）</li>
 *   <li>{@code final} —— 最终权威回答（整个流中只出现一次）</li>
 *   <li>{@code error} —— 执行失败（中文提示，不携带堆栈与密钥）</li>
 * </ul>
 */
public record ChatStreamEvent(String type, String data) {

    public static final String TYPE_STATUS = "status";
    public static final String TYPE_DELTA = "translated_delta";
    public static final String TYPE_FINAL = "final";
    public static final String TYPE_ERROR = "error";

    public static ChatStreamEvent status(String data) {
        return new ChatStreamEvent(TYPE_STATUS, data);
    }

    public static ChatStreamEvent delta(String data) {
        return new ChatStreamEvent(TYPE_DELTA, data);
    }

    public static ChatStreamEvent finalAnswer(String data) {
        return new ChatStreamEvent(TYPE_FINAL, data);
    }

    public static ChatStreamEvent error(String data) {
        return new ChatStreamEvent(TYPE_ERROR, data);
    }
}
