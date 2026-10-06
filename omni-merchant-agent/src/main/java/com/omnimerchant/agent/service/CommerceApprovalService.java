package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omnimerchant.agent.dto.CommerceDtos;
import com.omnimerchant.agent.dto.HelpdeskDtos;
import com.omnimerchant.agent.entity.CommerceActionPolicy;
import com.omnimerchant.agent.entity.CommerceActionRequest;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.entity.ReturnRequest;
import com.omnimerchant.agent.mapper.CommerceActionPolicyMapper;
import com.omnimerchant.agent.mapper.CommerceActionRequestMapper;
import com.omnimerchant.agent.mapper.ReturnRequestMapper;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 动作审批服务：把两个来源（ReturnRequest / CommerceActionRequest）聚合成统一审批视图。
 *
 * <p>复现自参考项目 {@code CommerceApprovalService}，保持原设计：
 * <ul>
 *   <li>两类来源<b>不合并</b>成一张表，由本服务统一映射为 {@link HelpdeskDtos.ActionRequestVO}，
 *       用 {@code source} 区分（{@code return_request} / {@code commerce_action_request}）。</li>
 *   <li>{@code page} 合并 + 状态过滤 + createdAt 倒序 + 内存分页。</li>
 *   <li>{@code forConversation} 按 relatedOrderId 优先、否则 customerEmail 关联。</li>
 * </ul>
 *
 * <p>相对原项目的取舍：
 * <ul>
 *   <li>去掉 {@code SupportAuditService} 调用（审计模块尚未复现），保留 TODO。</li>
 *   <li>审批<b>只改内部状态</b>，绝不调用 Shopify 等外部写操作；策略
 *       {@code externalWriteEnabled} 恒为 0。</li>
 * </ul>
 *
 * <p><b>ReturnRequest.status 数字含义（原项目存在冲突，此处显式定案）：</b>
 * <pre>
 *   来源                        1        2          3          4          5
 *   DDL 注释                  pending  reviewing  approved   rejected   done
 *   前端 ActionsView.vue      待审批   审核中     已批准      已拒绝      —
 *   原 CommerceApprovalService 待审批   已批准     已拒绝      已执行      —
 * </pre>
 * 原 Service 的 approve 写 2、reject 写 3，与 DDL/前端（3=批准、4=拒绝）相反，
 * 会导致前端 {@code finished([3,4,5])} 认不出 2，批准后按钮仍可点。
 * 本工程以 <b>DDL 注释 + 前端</b>（2 票且交互正确）为基准：
 * {@code approve → 3}、{@code reject → 4}，标签同步为
 * 「1待审批 2审核中 3已批准 4已拒绝 5已完成」。{@code resolution} 仍写
 * {@code APPROVED_MANUAL} / {@code REJECTED}。{@code pendingCount} 用 {@code status=1}。
 */
@Service
@RequiredArgsConstructor
public class CommerceApprovalService {

    private final CommerceActionPolicyMapper actionPolicyMapper;
    private final ReturnRequestMapper returnRequestMapper;
    private final CommerceActionRequestMapper actionRequestMapper;

    public List<HelpdeskDtos.CommerceActionPolicyVO> policies() {
        return actionPolicyMapper.selectList(new LambdaQueryWrapper<CommerceActionPolicy>()
                        .orderByDesc(CommerceActionPolicy::getActive)
                        .orderByAsc(CommerceActionPolicy::getActionType))
                .stream().map(this::toPolicyView).toList();
    }

    public CommerceDtos.PageResult<HelpdeskDtos.ActionRequestVO> page(String status, int page, int size) {
        var records = new ArrayList<HelpdeskDtos.ActionRequestVO>();
        returnRequestMapper.selectList(new LambdaQueryWrapper<ReturnRequest>()
                        .orderByDesc(ReturnRequest::getCreatedAt).last("LIMIT 500"))
                .stream().map(this::toReturnView).forEach(records::add);
        actionRequestMapper.selectList(new LambdaQueryWrapper<CommerceActionRequest>()
                        .orderByDesc(CommerceActionRequest::getCreatedAt).last("LIMIT 500"))
                .stream().map(this::toActionView).forEach(records::add);
        var filtered = records.stream()
                .filter(row -> status == null || status.isBlank() || status.equalsIgnoreCase(row.status()))
                .sorted(Comparator.comparing(HelpdeskDtos.ActionRequestVO::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        var pageSize = clamp(size);
        var from = Math.max(0, (page - 1) * pageSize);
        var to = Math.min(filtered.size(), from + pageSize);
        return new CommerceDtos.PageResult<>(filtered.size(), from >= filtered.size() ? List.of() : filtered.subList(from, to));
    }

    public List<HelpdeskDtos.ActionRequestVO> forConversation(Conversation conversation) {
        var orderNumber = conversation.getRelatedOrderId();
        var email = conversation.getCustomerEmail();
        if ((orderNumber == null || orderNumber.isBlank()) && (email == null || email.isBlank())) {
            return List.of();
        }
        var returns = new LambdaQueryWrapper<ReturnRequest>();
        var actions = new LambdaQueryWrapper<CommerceActionRequest>();
        if (orderNumber != null && !orderNumber.isBlank()) {
            returns.eq(ReturnRequest::getExternalOrderNumber, orderNumber);
            actions.eq(CommerceActionRequest::getExternalOrderNumber, orderNumber);
        } else {
            returns.eq(ReturnRequest::getCustomerEmail, email);
            actions.eq(CommerceActionRequest::getCustomerEmail, email);
        }
        var rows = new ArrayList<HelpdeskDtos.ActionRequestVO>();
        returnRequestMapper.selectList(returns.orderByDesc(ReturnRequest::getCreatedAt).last("LIMIT 10"))
                .stream().map(this::toReturnView).forEach(rows::add);
        actionRequestMapper.selectList(actions.orderByDesc(CommerceActionRequest::getCreatedAt).last("LIMIT 10"))
                .stream().map(this::toActionView).forEach(rows::add);
        return rows.stream().sorted(Comparator.comparing(HelpdeskDtos.ActionRequestVO::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    public long pendingCount() {
        var returns = returnRequestMapper.selectCount(new LambdaQueryWrapper<ReturnRequest>()
                .eq(ReturnRequest::getStatus, 1));
        var actions = actionRequestMapper.selectCount(new LambdaQueryWrapper<CommerceActionRequest>()
                .in(CommerceActionRequest::getStatus, List.of("PENDING_APPROVAL", "REQUESTED", "NEEDS_APPROVAL")));
        return returns + actions;
    }

    @Transactional
    public HelpdeskDtos.ActionRequestVO approve(String source, Long id, HelpdeskDtos.ActionDecisionRequest request) {
        // 审批只改内部状态；不调用任何外部电商写 API。
        requireActor(request == null ? null : request.actorId());
        if ("return_request".equals(source)) {
            var row = requireReturn(id);
            row.setStatus(3);                       // 3 = approved（见类注释的状态定案）
            row.setResolution("APPROVED_MANUAL");
            row.setResolutionNote(request.note());
            returnRequestMapper.updateById(row);
            // TODO 审计模块复现后，恢复 supportAuditService.record(...)
            return toReturnView(row);
        }
        if ("commerce_action_request".equals(source)) {
            var row = requireAction(id);
            row.setStatus("APPROVED_MANUAL");
            row.setApprovedBy(request.actorId());
            row.setApprovedAt(LocalDateTime.now());
            row.setExternalResult("Manual approval recorded; no external ecommerce write was executed by AI.");
            actionRequestMapper.updateById(row);
            // TODO 审计模块复现后，恢复 supportAuditService.record(...)
            return toActionView(row);
        }
        // 只允许两个已知来源，避免 source 传错时静默落到另一张表上误改数据。
        throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的审批来源: " + source);
    }

    @Transactional
    public HelpdeskDtos.ActionRequestVO reject(String source, Long id, HelpdeskDtos.ActionDecisionRequest request) {
        requireActor(request == null ? null : request.actorId());
        if ("return_request".equals(source)) {
            var row = requireReturn(id);
            row.setStatus(4);                       // 4 = rejected（见类注释的状态定案）
            row.setResolution("REJECTED");
            row.setResolutionNote(request.note());
            returnRequestMapper.updateById(row);
            // TODO 审计模块复现后，恢复 supportAuditService.record(...)
            return toReturnView(row);
        }
        if ("commerce_action_request".equals(source)) {
            var row = requireAction(id);
            row.setStatus("REJECTED");
            row.setExternalResult(request.note());
            actionRequestMapper.updateById(row);
            // TODO 审计模块复现后，恢复 supportAuditService.record(...)
            return toActionView(row);
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的审批来源: " + source);
    }

    private HelpdeskDtos.ActionRequestVO toReturnView(ReturnRequest row) {
        return new HelpdeskDtos.ActionRequestVO("return_request", row.getId(), row.getRequestNo(), row.getRequestType(),
                String.valueOf(row.getStatus()), returnStatusLabel(row.getStatus()), row.getExternalOrderNumber(),
                row.getCustomerEmail(), row.getAmount() == null ? null : row.getAmount().toPlainString(), row.getCurrency(),
                row.getApprovalRequiredReason(), row.getRequestedItems(), row.getResolution(), row.getResolutionNote(),
                row.getCreatedAt(), row.getUpdatedAt());
    }

    private HelpdeskDtos.ActionRequestVO toActionView(CommerceActionRequest row) {
        return new HelpdeskDtos.ActionRequestVO("commerce_action_request", row.getId(), row.getRequestNo(), row.getActionType(),
                row.getStatus(), actionStatusLabel(row.getStatus()), row.getExternalOrderNumber(), row.getCustomerEmail(),
                null, null, row.getRiskReason(), row.getRequestedPayload(), row.getExternalResult(), null,
                row.getCreatedAt(), row.getUpdatedAt());
    }

    private HelpdeskDtos.CommerceActionPolicyVO toPolicyView(CommerceActionPolicy policy) {
        return new HelpdeskDtos.CommerceActionPolicyVO(policy.getId(), policy.getActionType(), policy.getApprovalRequired(),
                policy.getMinApproverRole(), policy.getAmountThreshold() == null ? null : policy.getAmountThreshold().toPlainString(),
                policy.getRequiresIdentityVerification(), policy.getIdempotencyWindowMinutes(), policy.getExternalWriteEnabled(),
                policy.getPolicyNote(), policy.getActive());
    }

    private ReturnRequest requireReturn(Long id) {
        var row = returnRequestMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "审批请求不存在");
        }
        return row;
    }

    private CommerceActionRequest requireAction(Long id) {
        var row = actionRequestMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "审批请求不存在");
        }
        return row;
    }

    private Long requireActor(Long actorId) {
        if (actorId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "缺少操作者身份");
        }
        return actorId;
    }

    /** ReturnRequest 状态文案。采用 DDL 注释 + 前端语义（见类注释）。 */
    private String returnStatusLabel(Integer status) {
        return switch (status == null ? 0 : status) {
            case 1 -> "待审批";
            case 2 -> "审核中";
            case 3 -> "已批准";
            case 4 -> "已拒绝";
            case 5 -> "已完成";
            default -> "未知";
        };
    }

    private String actionStatusLabel(String status) {
        return switch (status == null || status.isBlank() ? "PENDING_APPROVAL" : status) {
            case "APPROVED_MANUAL" -> "已人工批准";
            case "REJECTED" -> "已拒绝";
            case "EXECUTED" -> "已执行";
            case "FAILED" -> "执行失败";
            default -> "待人工审批";
        };
    }

    private int clamp(int size) {
        return Math.max(1, Math.min(size, 100));
    }
}
