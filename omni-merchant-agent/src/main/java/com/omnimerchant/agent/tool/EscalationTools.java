package com.omnimerchant.agent.tool;

import com.omnimerchant.agent.escalation.EscalationResult;
import com.omnimerchant.agent.escalation.EscalationService;
import com.omnimerchant.agent.service.ToolAuditService;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring AI 工具：人工客服升级。
 * 当 AI 无法解决问题时，由 LLM 调用 escalateToHuman。
 *
 * <p>复现自参考项目 {@code tool/EscalationTools}。工具只调用
 * {@link EscalationService}，由其写入 EscalationRecord 并（投影）生成 Ticket；
 * 工具不直接 insert 任何表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EscalationTools {

    private final EscalationService escalationService;
    private final ToolAuditService toolAuditService;
    /** 升级原因的最大长度。 */
    private static final int MAX_REASON_LENGTH = 128;
    /** 摘要的最大长度。 */
    private static final int MAX_SUMMARY_LENGTH = 1000;

    @Tool(description = """
            将对话升级给人工客服。\
            在以下情况使用本工具：客户明确要求转人工、\
            AI 多次尝试仍无法解决、\
            涉及高价值或敏感事项（争议金额超过 $100）、\
            客户情绪强烈负面或愤怒、\
            或对答案的置信度低于 75%。\
            返回工单号和预计等待时间。
            """)
    public EscalationResult escalateToHuman(
            @ToolParam(description = "升级的主要原因（如：无法解决、客户要求、高价值争议）")
            String reason,
            @ToolParam(description = "客户问题简述，以及目前已尝试的处理")
            String summary,
            @ToolParam(description = "优先级：1=低，2=中，3=高，4=紧急")
            int priority) {
        var tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            log.warn("escalateToHuman 被拒绝：缺少租户上下文");
            return new EscalationResult("UNAVAILABLE", 0, "MISSING_TENANT_CONTEXT",
                    "没有已核验的租户上下文时无法转人工。");
        }

        var safePriority = Math.max(1, Math.min(priority, 4));
        try {
            log.info("escalateToHuman 请求：tenant={}, priority={}", tenantId, safePriority);
            return toolAuditService.record("escalateToHuman",
                    params("reason", trim(reason, MAX_REASON_LENGTH), "priority", safePriority),
                    () -> escalationService.escalate(trim(reason, MAX_REASON_LENGTH),
                            trim(summary, MAX_SUMMARY_LENGTH), safePriority));
        } catch (Exception e) {
            log.error("escalateToHuman 失败：{}", e.getMessage());
            return new EscalationResult("UNAVAILABLE", 0, "FAILED",
                    "转人工暂不可用，请引导客户直接联系客服。");
        }
    }

    /** 截断超长文本，避免超出字段上限。 */
    private String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /** 把 key/value 交替的参数拼成有序 Map，用于审计日志。 */
    private Map<String, Object> params(Object... entries) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }
}
