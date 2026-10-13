package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.entity.*;
import com.omnimerchant.agent.mapper.*;
import com.omnimerchant.agent.support.CurrentActor;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

/** 仅供有执行权限的人工调用；没有模型工具入口，也不执行支付或物流操作。 */
@Service
@RequiredArgsConstructor
public class CommerceActionExecutionService {
    private final ReturnRequestMapper returnMapper;
    private final CommerceActionRequestMapper actionMapper;
    private final CommerceActionPolicyMapper policyMapper;
    private final OrderInfoMapper orderMapper;
    private final CommerceActionAuditService audit;
    private final ObjectMapper json;
    private final PlatformTransactionManager transactionManager;

    public CommerceActionRecord execute(String source, Long id) {
        requirePermission("action:execute");
        var transaction = new TransactionTemplate(transactionManager);
        try {
            return transaction.execute(status -> executeLocked(source, id));
        } catch (RuntimeException failure) {
            // 主事务已回滚。只有可见的已批准请求才记录失败，记录失败不能掩盖原错误。
            try {
                transaction.executeWithoutResult(status -> {
                    var target = load(source, id);
                    if (target.approved() && audit.execution(source, id) == null) {
                        audit.record(source, id, target.type(), "EXECUTION", "FAILED", null,
                                failure instanceof BusinessException ? failure.getMessage() : "本地执行失败，订单修改已回滚", null, null);
                    }
                });
            } catch (RuntimeException ignored) {
                // 数据库不可用时无法另外写入失败审计，仍返回原始失败。
            }
            throw failure;
        }
    }

    public List<CommerceActionRecord> history(String source, Long id) {
        requirePermission("action:approve");
        load(source, id);
        return audit.history(source, id);
    }

    private CommerceActionRecord executeLocked(String source, Long id) {
        var target = load(source, id);
        if (!Objects.equals(target.tenantId(), TenantContextHolder.get())) throw forbidden("申请租户不一致");
        if (Set.of("CANCEL_ORDER", "ADDRESS_CHANGE").contains(target.type())) requirePolicy(target.type());
        var completed = audit.execution(source, id);
        if (completed != null) return completed;
        if (!target.approved()) throw bad("申请尚未批准或已拒绝，不能执行");
        if (!Set.of("CANCEL_ORDER", "ADDRESS_CHANGE").contains(target.type())) {
            throw bad("此申请已批准，等待人工处理；当前没有支付退款或补发执行能力");
        }
        var orders = orderMapper.selectList(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getTenantId, target.tenantId())
                .eq(OrderInfo::getExternalOrderNumber, target.orderNumber()).last("LIMIT 2 FOR UPDATE"));
        if (orders.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "申请关联的本租户订单不存在");
        if (orders.size() != 1) throw bad("订单号关联不唯一，请人工核实订单后重新提交申请");
        var order = orders.get(0);
        if (!Objects.equals(order.getTenantId(), target.tenantId())) throw forbidden("订单与申请租户不一致");
        if (target.actionRequest() != null && target.actionRequest().getExternalOrderId() != null
                && !target.actionRequest().getExternalOrderId().isBlank()
                && !Objects.equals(target.actionRequest().getExternalOrderId(), order.getExternalOrderId())) {
            throw bad("申请中的订单标识与实际订单不一致");
        }
        if (!matchesOwner(order, target.customer())) throw forbidden("申请中的客户身份与订单不一致，不能执行");
        requireEditable(order);
        var before = snapshot(order);
        if ("CANCEL_ORDER".equals(target.type())) {
            order.setOrderStatus("cancelled");
            order.setCancelledAt(LocalDateTime.now());
        } else {
            var address = validatedAddress(target.payload());
            order.setShippingAddress(address.toString());
            order.setShippingCountry(address.get("country").asText());
            order.setShippingState(address.get("state").asText());
            order.setShippingZip(address.get("zip").asText());
        }
        // 行锁负责同库串行化，乐观锁负责拒绝其他写入口造成的版本冲突。
        if (orderMapper.updateById(order) != 1) throw bad("订单版本冲突，请重新读取订单后处理");
        var note = "CANCEL_ORDER".equals(target.type())
                ? "本地订单已取消；支付状态未修改，退款仍需人工处理"
                : "本地收货地址已更新；未执行外部平台写入";
        var result = audit.record(source, id, target.type(), "EXECUTION", "EXECUTED", order.getId(), note, before, snapshot(order));
        if (target.returnRequest() != null) {
            var request = target.returnRequest();
            request.setStatus(5);
            request.setResolution("EXECUTED_LOCAL");
            request.setResolutionNote(note);
            if (returnMapper.updateById(request) != 1) throw bad("申请状态更新失败，订单修改已回滚");
        } else {
            var request = target.actionRequest();
            request.setStatus("EXECUTED");
            request.setExecutedAt(LocalDateTime.now());
            request.setExternalResult(note);
            if (actionMapper.updateById(request) != 1) throw bad("申请状态更新失败，订单修改已回滚");
        }
        return result;
    }

    public void requirePolicy(String type) {
        var policy = policyMapper.selectOne(new LambdaQueryWrapper<CommerceActionPolicy>()
                .eq(CommerceActionPolicy::getTenantId, TenantContextHolder.get())
                .eq(CommerceActionPolicy::getActionType, type).eq(CommerceActionPolicy::getActive, 1).last("LIMIT 1"));
        if (policy == null) throw forbidden("缺少启用的动作策略，禁止执行");
        var principal = CurrentActor.principal();
        var roles = principal.tenantRoles().getOrDefault(TenantContextHolder.get(), Set.of());
        var minimum = policy.getMinApproverRole();
        var ranks = Map.of("SUPPORT_AGENT", 1, "SUPPORT_SUPERVISOR", 2, "TENANT_ADMIN", 3);
        var required = ranks.get(minimum);
        if (required == null || (!principal.platformAdmin() && roles.stream()
                .noneMatch(role -> ranks.getOrDefault(role, 0) >= required))) {
            throw forbidden("当前角色不满足动作策略要求");
        }
        // 本服务即使策略外部开关开启也只执行本地操作。身份始终必须与订单匹配。
    }

    public static void requirePermission(String permission) {
        var principal = CurrentActor.principal();
        var tenantId = TenantContextHolder.get();
        if (principal == null || principal.userId() == null || !principal.canAccessTenant(tenantId)
                || !principal.hasPermission(tenantId, permission)) throw forbidden("无权执行该操作");
    }

    private Target load(String source, Long id) {
        var tenant = TenantContextHolder.get();
        if ("return_request".equals(source)) {
            var row = returnMapper.selectOne(new LambdaQueryWrapper<ReturnRequest>()
                    .eq(ReturnRequest::getId, id).eq(ReturnRequest::getTenantId, tenant).last("FOR UPDATE"));
            if (row == null) throw new BusinessException(ErrorCode.NOT_FOUND, "审批请求不存在");
            return new Target(row.getTenantId(), row.getRequestType(), row.getExternalOrderNumber(), row.getCustomerEmail(),
                    row.getRequestedItems(), Integer.valueOf(3).equals(row.getStatus()) && "APPROVED_MANUAL".equals(row.getResolution()), row, null);
        }
        if ("commerce_action_request".equals(source)) {
            var row = actionMapper.selectOne(new LambdaQueryWrapper<CommerceActionRequest>()
                    .eq(CommerceActionRequest::getId, id).eq(CommerceActionRequest::getTenantId, tenant).last("FOR UPDATE"));
            if (row == null) throw new BusinessException(ErrorCode.NOT_FOUND, "审批请求不存在");
            return new Target(row.getTenantId(), row.getActionType(), row.getExternalOrderNumber(), row.getCustomerEmail(),
                    row.getRequestedPayload(), "APPROVED_MANUAL".equals(row.getStatus()), null, row);
        }
        throw bad("不支持的审批来源");
    }

    public static void requireEditable(OrderInfo order) {
        if (!Set.of("DEMO", "LOCAL").contains(order.getSyncSource() == null ? "" : order.getSyncSource())) {
            throw bad("仅允许执行本地演示订单，其他来源订单等待人工处理");
        }
        if (!Set.of("pending", "unpaid", "paid", "processing").contains(value(order.getOrderStatus()))
                || !Set.of("unpaid", "pending", "paid", "authorized").contains(value(order.getPaymentStatus()))
                || !Set.of("unfulfilled", "pending").contains(value(order.getFulfillmentStatus()))
                || order.getShippedAt() != null || order.getActualDeliveryAt() != null
                || order.getCancelledAt() != null || (order.getTrackingNumber() != null && !order.getTrackingNumber().isBlank())
                || (order.getRefundedAmount() != null && order.getRefundedAmount().signum() > 0)) {
            throw bad("订单已进入发货、完成、取消或退款阶段，不能取消或修改地址");
        }
    }

    /** 必须使用已审批申请中的结构化地址，执行接口不接受替换地址参数。 */
    public JsonNode validatedAddress(String payload) {
        try {
            var root = json.readTree(payload);
            var address = root.get("newAddress");
            if (address != null && address.isTextual()) address = json.readTree(address.asText());
            if (address == null || !address.isObject()) throw bad("缺少经过审批的结构化新地址，请重新提交申请");
            var normalized = json.createObjectNode();
            var labels = Map.of("recipient", "收件人", "phone", "手机号", "address", "详细地址", "country", "国家", "state", "省市", "zip", "邮编");
            for (var field : List.of("recipient", "phone", "address", "country", "state", "zip")) {
                var node = address.get(field);
                if (node == null || !node.isTextual() || node.asText().isBlank()) throw bad("新地址缺少有效字段：" + labels.get(field));
                var text = node.asText().trim();
                if (text.length() > ("address".equals(field) ? 500 : "state".equals(field) ? 64 : 100) || text.chars().anyMatch(Character::isISOControl)) {
                    throw bad("新地址字段格式不合法：" + labels.get(field));
                }
                normalized.put(field, text);
            }
            if (!"CN".equalsIgnoreCase(normalized.get("country").asText())
                    || !normalized.get("zip").asText().matches("[0-9]{6}")
                    || !normalized.get("phone").asText().matches("[+0-9() -]{6,30}")
                    || normalized.get("address").asText().length() < 5) throw bad("请提供有效的中国收货地址、手机号和六位邮编");
            normalized.put("country", "CN");
            return normalized;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw bad("新地址必须是完整的结构化数据，请重新提交申请");
        }
    }

    private String snapshot(OrderInfo order) {
        var data = new LinkedHashMap<String, Object>();
        data.put("orderStatus", order.getOrderStatus());
        data.put("paymentStatus", order.getPaymentStatus());
        data.put("fulfillmentStatus", order.getFulfillmentStatus());
        data.put("shippingAddress", order.getShippingAddress());
        data.put("shippingCountry", order.getShippingCountry());
        data.put("shippingState", order.getShippingState());
        data.put("shippingZip", order.getShippingZip());
        data.put("cancelledAt", order.getCancelledAt() == null ? null : order.getCancelledAt().toString());
        data.put("version", order.getVersion());
        try { return json.writeValueAsString(data); }
        catch (Exception e) { throw bad("执行审计数据生成失败"); }
    }

    private boolean matchesOwner(OrderInfo order, String customer) {
        return customer != null && !customer.isBlank() && (customer.trim().equalsIgnoreCase(order.getCustomerEmail())
                || customer.trim().equals(order.getCustomerPhone()));
    }
    private static String value(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
    private static BusinessException forbidden(String message) { return new BusinessException(ErrorCode.FORBIDDEN, message); }
    private record Target(Long tenantId, String type, String orderNumber, String customer, String payload,
                          boolean approved, ReturnRequest returnRequest, CommerceActionRequest actionRequest) {}
}
