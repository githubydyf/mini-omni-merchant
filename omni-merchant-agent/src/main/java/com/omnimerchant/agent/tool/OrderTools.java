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
 * Spring AI 工具：订单查询与客户诉求创建。
 *
 * <p>复现自参考项目 {@code tool/OrderTools}。工具只调用已有的
 * {@link CommercePlatformService}，不直接操作 Mapper，也不重新实现业务逻辑。
 * 所有调用都经过 {@link ToolAuditService} 写入 tool_call_log；参数审计中不直接记录
 * customerEmail，只记录 {@code customerEmailProvided} 布尔值（避免明文 PII 落库）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTools {

    /** 订单号格式：可带 # 前缀，长度 2~64，允许字母数字与 _ -。 */
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("^(#?[A-Za-z0-9][A-Za-z0-9_-]{1,63})$");
    private final CommercePlatformService commerceService;
    private final ToolAuditService toolAuditService;

    @Tool(description = """
            根据订单号查询订单详细信息。\
            返回订单状态、商品明细、订单金额、物流单号与收货地址。\
            当客户询问某个具体订单时使用本工具 —— \
            包括查询状态、预计送达时间、订单内容或收货信息。
            """)
    public OrderQueryResult queryOrder(
            @ToolParam(description = "订单号，格式如 #12345 或 ORD-XXXXX")
            String orderId,
            @ToolParam(description = "用于身份核验的客户邮箱", required = false)
            String customerEmail) {
        var tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            log.warn("queryOrder 被拒绝：缺少租户上下文");
            return unavailable(orderId, "MISSING_TENANT_CONTEXT",
                    "订单查询需要已核验的租户上下文。");
        }

        if (orderId == null || !ORDER_ID_PATTERN.matcher(orderId).matches()) {
            log.warn("queryOrder 被拒绝：订单号格式非法, tenant={}", tenantId);
            return unavailable(orderId, "INVALID_ORDER_ID",
                    "订单号格式非法。");
        }

        return toolAuditService.record("queryOrder", params("orderId", orderId, "customerEmailProvided",
                        customerEmail != null && !customerEmail.isBlank()),
                () -> {
                    var lookup = commerceService.queryOrder(orderId, customerEmail);
                    return new OrderQueryResult(
                            lookup.orderId(),
                            lookup.status(),
                            lookup.verified(),
                            lookup.items(),
                            lookup.totalAmount() == null ? null : lookup.totalAmount().toPlainString(),
                            lookup.currency(),
                            lookup.trackingNumber(),
                            lookup.trackingCarrier(),
                            lookup.trackingStatus(),
                            lookup.shippingAddress(),
                            lookup.message());
                });
    }

    @Tool(description = """
            为已核验的订单创建内部退货申请。\
            本工具不会退款，也不会修改外部电商平台。\
            当客户想要退货时，它只创建一条待人工审核的申请。
            """)
    public CommercePlatformService.ReturnActionResult createReturnRequest(
            @ToolParam(description = "订单号或订单 ID") String orderId,
            @ToolParam(description = "用于订单归属核验的客户邮箱或手机号") String customerEmail,
            @ToolParam(description = "退货原因") String reason,
            @ToolParam(description = "描述退货商品的 JSON 或简短文本", required = false) String items) {
        if (TenantContextHolder.get() == null) {
            return CommercePlatformService.ReturnActionResult.rejected(orderId, "MISSING_TENANT_CONTEXT",
                    "退货申请需要已核验的租户上下文。");
        }
        return toolAuditService.record("createReturnRequest",
                params("orderId", orderId, "customerEmailProvided", customerEmail != null && !customerEmail.isBlank()),
                () -> commerceService.createReturnRequest(orderId, customerEmail, reason, items));
    }

    @Tool(description = """
            为已核验的订单申请退款或补发。\
            AI 绝不直接执行退款；本工具只创建一条待人工审批的申请。
            """)
    public CommercePlatformService.ReturnActionResult requestRefundOrReplacement(
            @ToolParam(description = "订单号或订单 ID") String orderId,
            @ToolParam(description = "用于订单归属核验的客户邮箱或手机号") String customerEmail,
            @ToolParam(description = "申请的动作：refund（退款）或 replacement（补发）") String action,
            @ToolParam(description = "申请原因与上下文") String reason) {
        if (TenantContextHolder.get() == null) {
            return CommercePlatformService.ReturnActionResult.rejected(orderId, "MISSING_TENANT_CONTEXT",
                    "退款/补发申请需要已核验的租户上下文。");
        }
        return toolAuditService.record("requestRefundOrReplacement",
                params("orderId", orderId, "action", action, "customerEmailProvided",
                        customerEmail != null && !customerEmail.isBlank()),
                () -> commerceService.requestRefundOrReplacement(orderId, customerEmail, action, reason));
    }

    @Tool(description = """
            为已核验的订单申请修改收货地址。\
            AI 绝不直接写入外部订单；本工具只创建一条待人工审批的申请。
            """)
    public CommercePlatformService.ReturnActionResult requestAddressChange(
            @ToolParam(description = "订单号或订单 ID") String orderId,
            @ToolParam(description = "用于订单归属核验的客户邮箱或手机号") String customerEmail,
            @ToolParam(description = "客户申请的新收货地址") String newAddress) {
        if (TenantContextHolder.get() == null) {
            return CommercePlatformService.ReturnActionResult.rejected(orderId, "MISSING_TENANT_CONTEXT",
                    "改地址申请需要已核验的租户上下文。");
        }
        return toolAuditService.record("requestAddressChange",
                params("orderId", orderId, "customerEmailProvided", customerEmail != null && !customerEmail.isBlank()),
                () -> commerceService.requestAddressChange(orderId, customerEmail, newAddress));
    }

    /** 租户上下文缺失或订单号非法时的占位返回（不编造订单数据）。 */
    private OrderQueryResult unavailable(String orderId, String reason, String message) {
        return new OrderQueryResult(
                orderId,
                reason,
                false,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
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

    /** 订单查询结果（字段与参考项目一致）。 */
    public record OrderQueryResult(
            String orderId,
            String status,
            boolean verified,
            List<Map<String, Object>> items,
            String totalAmount,
            String currency,
            String trackingNumber,
            String trackingCarrier,
            String trackingStatus,
            String shippingAddress,
            String message) {
    }
}
