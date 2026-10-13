package com.omnimerchant.agent.controller;

import com.omnimerchant.agent.dto.HelpdeskDtos;
import com.omnimerchant.agent.service.CommerceApprovalService;
import com.omnimerchant.agent.service.CommercePlatformService;
import com.omnimerchant.agent.service.CommercialOpsService;
import com.omnimerchant.agent.service.CommerceActionExecutionService;
import com.omnimerchant.agent.service.CommerceLocalRequestService;
import com.omnimerchant.common.dto.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一收件箱（Inbox）+ 工单（Ticket）+ 动作审批（Action Approval）接口。
 *
 * <p>复现自参考项目 {@code CommercialOpsController}，本阶段按原项目恢复
 * {@code @PreAuthorize} 权限校验（权限来自已验证 JWT + 当前租户 + 数据库角色成员关系）：
 * <ul>
 *   <li>工单分配 / 解决：{@code ticket:assign} / {@code ticket:resolve}</li>
 *   <li>人工接管 / 回复：{@code inbox:takeover} / {@code inbox:reply}</li>
 *   <li>售后动作审批：{@code action:approve}</li>
 * </ul>
 *
 * <p>操作人身份由 {@code CurrentActor}（{@code SecurityContextHolder} 中的 JwtPrincipal）
 * 提供，请求体中的 {@code agentId} / {@code actorId} 不再作为操作者来源。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CommercialOpsController {

    private final CommercialOpsService service;
    private final CommerceApprovalService approvalService;
    private final CommercePlatformService commerceService;
    private final CommerceActionExecutionService executionService;
    private final CommerceLocalRequestService localRequestService;

    @GetMapping("/inbox/queues")
    @PreAuthorize("@tenantAuthorization.hasPermission('inbox:reply')")
    public R<?> inboxQueues() {
        return R.ok(service.inboxQueues());
    }

    @GetMapping("/inbox/items")
    @PreAuthorize("@tenantAuthorization.hasPermission('inbox:reply')")
    public R<?> inboxItems(@RequestParam(required = false) String queue,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size) {
        return R.ok(service.inboxItems(queue, page, size));
    }

    @GetMapping("/inbox/{conversationUuid}/context")
    @PreAuthorize("@tenantAuthorization.hasPermission('inbox:reply')")
    public R<?> inboxContext(@PathVariable String conversationUuid) {
        return R.ok(service.inboxContext(conversationUuid));
    }

    @PostMapping("/inbox/{conversationUuid}/takeover")
    @PreAuthorize("@tenantAuthorization.hasPermission('inbox:takeover')")
    public R<?> takeover(@PathVariable String conversationUuid,
                         @RequestBody(required = false) HelpdeskDtos.TakeoverRequest request) {
        return R.ok(service.takeover(conversationUuid, request));
    }

    @PostMapping("/inbox/{conversationUuid}/reply")
    @PreAuthorize("@tenantAuthorization.hasPermission('inbox:reply')")
    public R<?> humanReply(@PathVariable String conversationUuid,
                           @RequestBody HelpdeskDtos.HumanReplyRequest request) {
        return R.ok(service.humanReply(conversationUuid, request));
    }

    @GetMapping("/tickets")
    @PreAuthorize("@tenantAuthorization.hasPermission('ticket:resolve')")
    public R<?> tickets(@RequestParam(required = false) String status,
                        @RequestParam(defaultValue = "1") int page,
                        @RequestParam(defaultValue = "20") int size) {
        return R.ok(service.tickets(status, page, size));
    }

    @PostMapping("/tickets/{id}/assign")
    @PreAuthorize("@tenantAuthorization.hasPermission('ticket:assign')")
    public R<?> assignTicket(@PathVariable Long id,
                             @RequestBody(required = false) HelpdeskDtos.TakeoverRequest request) {
        return R.ok(service.assignTicket(id, request));
    }

    @PostMapping("/tickets/{id}/resolve")
    @PreAuthorize("@tenantAuthorization.hasPermission('ticket:resolve')")
    public R<?> resolveTicket(@PathVariable Long id,
                              @RequestBody(required = false) HelpdeskDtos.ActionDecisionRequest request) {
        return R.ok(service.resolveTicket(id, request));
    }

    // ---- 动作审批（Action Approval）----

    @GetMapping("/actions")
    @PreAuthorize("@tenantAuthorization.hasPermission('action:approve')")
    public R<?> actions(@RequestParam(required = false) String status,
                        @RequestParam(defaultValue = "1") int page,
                        @RequestParam(defaultValue = "20") int size) {
        return R.ok(approvalService.page(status, page, size));
    }

    @GetMapping("/actions/policies")
    @PreAuthorize("@tenantAuthorization.hasPermission('action:approve')")
    public R<?> actionPolicies() {
        return R.ok(approvalService.policies());
    }

    @PostMapping("/actions/{source}/{id}/approve")
    @PreAuthorize("@tenantAuthorization.hasPermission('action:approve')")
    public R<?> approveAction(@PathVariable String source,
                              @PathVariable Long id,
                              @RequestBody(required = false) HelpdeskDtos.ActionDecisionRequest request) {
        return R.ok(approvalService.approve(source, id, request));
    }

    @PostMapping("/actions/{source}/{id}/reject")
    @PreAuthorize("@tenantAuthorization.hasPermission('action:approve')")
    public R<?> rejectAction(@PathVariable String source,
                             @PathVariable Long id,
                             @RequestBody(required = false) HelpdeskDtos.ActionDecisionRequest request) {
        return R.ok(approvalService.reject(source, id, request));
    }

    @PostMapping("/actions/{source}/{id}/execute")
    @PreAuthorize("@tenantAuthorization.hasPermission('action:execute')")
    public R<?> executeAction(@PathVariable String source, @PathVariable Long id) {
        return R.ok(executionService.execute(source, id));
    }

    @GetMapping("/actions/{source}/{id}/history")
    @PreAuthorize("@tenantAuthorization.hasPermission('action:approve')")
    public R<?> actionHistory(@PathVariable String source, @PathVariable Long id) {
        return R.ok(executionService.history(source, id));
    }

    @PostMapping("/actions/local-requests")
    @PreAuthorize("@tenantAuthorization.hasPermission('action:approve')")
    public R<?> createLocalAction(@RequestBody HelpdeskDtos.LocalActionCreate input) {
        return R.ok(localRequestService.create(input));
    }
}
