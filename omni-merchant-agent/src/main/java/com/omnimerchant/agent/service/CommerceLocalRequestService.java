package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.dto.HelpdeskDtos;
import com.omnimerchant.agent.entity.CommerceActionRequest;
import com.omnimerchant.agent.entity.OrderInfo;
import com.omnimerchant.agent.mapper.CommerceActionRequestMapper;
import com.omnimerchant.agent.mapper.OrderInfoMapper;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 人工整理客户诉求，重新创建待审批申请；不能修改已批准的申请内容。 */
@Service
@RequiredArgsConstructor
public class CommerceLocalRequestService {
    private final OrderInfoMapper orderMapper;
    private final CommerceActionRequestMapper requestMapper;
    private final CommerceActionExecutionService execution;
    private final ObjectMapper json;

    @Transactional
    public CommerceActionRequest create(HelpdeskDtos.LocalActionCreate input) {
        CommerceActionExecutionService.requirePermission("action:approve");
        if (input == null || input.orderNumber() == null || input.orderNumber().isBlank()
                || input.actionType() == null || !Set.of("CANCEL_ORDER", "ADDRESS_CHANGE").contains(input.actionType())
                || input.reason() == null || input.reason().isBlank() || input.reason().length() > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请提供订单号、支持的动作及客户申请原因");
        }
        execution.requirePolicy(input.actionType());
        var orders = orderMapper.selectList(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getTenantId, TenantContextHolder.get())
                .eq(OrderInfo::getExternalOrderNumber, input.orderNumber().trim()).last("LIMIT 2 FOR UPDATE"));
        if (orders.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "本租户订单不存在");
        if (orders.size() != 1) throw new BusinessException(ErrorCode.BAD_REQUEST, "订单号关联不唯一，请先核实订单");
        var order = orders.get(0);
        CommerceActionExecutionService.requireEditable(order);
        var row = new CommerceActionRequest();
        row.setTenantId(order.getTenantId());
        row.setRequestNo("LOCAL-" + UUID.randomUUID());
        row.setActionType(input.actionType());
        row.setPlatform("custom");
        row.setExternalOrderId(order.getExternalOrderId());
        row.setExternalOrderNumber(order.getExternalOrderNumber());
        row.setCustomerEmail(order.getCustomerEmail());
        row.setStatus("PENDING_APPROVAL");
        row.setRiskReason("人工登记客户诉求；待审批后另行执行本地操作");
        try {
            var payload = json.createObjectNode();
            payload.put("reason", input.reason().trim());
            if ("ADDRESS_CHANGE".equals(input.actionType())) {
                var wrapped = json.writeValueAsString(Map.of("newAddress", input.newAddress() == null ? Map.of() : input.newAddress()));
                payload.set("newAddress", execution.validatedAddress(wrapped));
            }
            row.setRequestedPayload(payload.toString());
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(ErrorCode.BAD_REQUEST, "申请内容格式不正确"); }
        requestMapper.insert(row);
        return row;
    }
}
