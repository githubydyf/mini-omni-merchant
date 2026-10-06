package com.omnimerchant.agent.tool;

import com.omnimerchant.agent.service.CommercePlatformService;
import com.omnimerchant.agent.service.ToolAuditService;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Spring AI 工具：物流轨迹查询。
 *
 * <p>复现自参考项目 {@code tool/LogisticsTools}。物流轨迹解析保留在
 * {@link CommercePlatformService#trackLogistics}，工具只做格式校验 + 转发 + 审计。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogisticsTools {

    /** 物流单号格式：长度 4~64，允许字母数字与 _ -。 */
    private static final Pattern TRACKING_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{3,63}$");
    private final CommercePlatformService commerceService;
    private final ToolAuditService toolAuditService;

    @Tool(description = """
            根据物流单号查询包裹轨迹。\
            返回当前状态、预计送达日期与轨迹节点历史。\
            当客户询问物流状态、预计送达时间或包裹位置时使用本工具。
            """)
    public LogisticsResult trackLogistics(
            @ToolParam(description = "承运商提供的物流单号（如 FedEx、UPS、DHL）")
            String trackingNumber) {
        var tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            log.warn("trackLogistics 被拒绝：缺少租户上下文");
            return unavailable(trackingNumber, "MISSING_TENANT_CONTEXT",
                    "物流查询需要已核验的租户上下文。");
        }

        if (trackingNumber == null || !TRACKING_PATTERN.matcher(trackingNumber).matches()) {
            log.warn("trackLogistics 被拒绝：物流单号格式非法, tenant={}", tenantId);
            return unavailable(trackingNumber, "INVALID_TRACKING_NUMBER",
                    "物流单号格式非法。");
        }

        return toolAuditService.record("trackLogistics", params("trackingNumber", trackingNumber), () -> {
            var lookup = commerceService.trackLogistics(trackingNumber);
            return new LogisticsResult(lookup.trackingNumber(), lookup.status(),
                    lookup.estimatedDelivery(), lookup.checkpoints(), lookup.message());
        });
    }

    /** 租户上下文缺失或单号非法时的占位返回（不编造物流数据）。 */
    private LogisticsResult unavailable(String trackingNumber, String reason, String message) {
        return new LogisticsResult(
                trackingNumber,
                reason,
                null,
                List.of(),
                message);
    }

    /** 把 key/value 交替的参数拼成有序 Map，用于审计日志。 */
    private Map<String, Object> params(Object... entries) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }

    /** 物流查询结果（字段与参考项目一致）。 */
    public record LogisticsResult(
            String trackingNumber,
            String status,
            String estimatedDelivery,
            List<Map<String, Object>> checkpoints,
            String message) {
    }
}
