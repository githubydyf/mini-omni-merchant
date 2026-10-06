package com.omnimerchant.agent.controller;

import com.omnimerchant.agent.dto.HelpdeskDtos;
import com.omnimerchant.agent.service.CommerceApprovalService;
import com.omnimerchant.agent.service.CommercePlatformService;
import com.omnimerchant.agent.service.CommercialOpsService;
import com.omnimerchant.common.dto.R;
import lombok.RequiredArgsConstructor;
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
 * <p>复现自参考项目 {@code CommercialOpsController} 中与本阶段相关的接口，
 * 路径、参数与响应结构与原项目保持一致；仅去掉了原项目依赖安全框架的
 * {@code @PreAuthorize} 与 {@code @AuthenticationPrincipal}（当前无登录 / RBAC）。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CommercialOpsController {

    private final CommercialOpsService service;
    private final CommerceApprovalService approvalService;
    private final CommercePlatformService commerceService;

    @GetMapping("/inbox/queues")
    public R<?> inboxQueues() {
        return R.ok(service.inboxQueues());
    }

    @GetMapping("/inbox/items")
    public R<?> inboxItems(@RequestParam(required = false) String queue,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size) {
        return R.ok(service.inboxItems(queue, page, size));
    }

    @GetMapping("/inbox/{conversationUuid}/context")
    public R<?> inboxContext(@PathVariable String conversationUuid) {
        return R.ok(service.inboxContext(conversationUuid));
    }

    @PostMapping("/inbox/{conversationUuid}/takeover")
    public R<?> takeover(@PathVariable String conversationUuid,
                         @RequestBody(required = false) HelpdeskDtos.TakeoverRequest request) {
        return R.ok(service.takeover(conversationUuid, request));
    }

    @PostMapping("/inbox/{conversationUuid}/reply")
    public R<?> humanReply(@PathVariable String conversationUuid,
                           @RequestBody HelpdeskDtos.HumanReplyRequest request) {
        return R.ok(service.humanReply(conversationUuid, request));
    }

    @GetMapping("/tickets")
    public R<?> tickets(@RequestParam(required = false) String status,
                        @RequestParam(defaultValue = "1") int page,
                        @RequestParam(defaultValue = "20") int size) {
        return R.ok(service.tickets(status, page, size));
    }

    @PostMapping("/tickets/{id}/assign")
    public R<?> assignTicket(@PathVariable Long id,
                             @RequestBody(required = false) HelpdeskDtos.TakeoverRequest request) {
        return R.ok(service.assignTicket(id, request));
    }

    @PostMapping("/tickets/{id}/resolve")
    public R<?> resolveTicket(@PathVariable Long id,
                              @RequestBody(required = false) HelpdeskDtos.ActionDecisionRequest request) {
        return R.ok(service.resolveTicket(id, request));
    }

    // ---- 动作审批（Action Approval）----

    @GetMapping("/actions")
    public R<?> actions(@RequestParam(required = false) String status,
                        @RequestParam(defaultValue = "1") int page,
                        @RequestParam(defaultValue = "20") int size) {
        return R.ok(approvalService.page(status, page, size));
    }

    @GetMapping("/actions/policies")
    public R<?> actionPolicies() {
        return R.ok(approvalService.policies());
    }

    // ------------------------------------------------------------------
    // 客户诉求创建（ReturnRequest）。
    // 原项目这三个动作只挂在 AI 工具（OrderTools）上，没有 HTTP 入口；
    // 这里为方便后续调用补充 REST 入口，逻辑完全复用 CommercePlatformService
    // 中已复现的原项目方法，均需先通过订单归属核验（否则返回 rejected 状态）。
    // ------------------------------------------------------------------

//    @PostMapping("/actions/return_request")
//    public R<?> createReturnRequest(@RequestBody HelpdeskDtos.ReturnRequestCreate request) {
//        return R.ok(commerceService.createReturnRequest(request.orderNumber(), request.customerEmail(),
//                request.reason(), request.items()));
//    }
//
//    @PostMapping("/actions/refund_or_replacement")
//    public R<?> createRefundOrReplacement(@RequestBody HelpdeskDtos.RefundOrReplacementCreate request) {
//        return R.ok(commerceService.requestRefundOrReplacement(request.orderNumber(), request.customerEmail(),
//                request.action(), request.reason()));
//    }
//
//    @PostMapping("/actions/address_change")
//    public R<?> createAddressChange(@RequestBody HelpdeskDtos.AddressChangeCreate request) {
//        return R.ok(commerceService.requestAddressChange(request.orderNumber(), request.customerEmail(),
//                request.newAddress()));
//    }

    @PostMapping("/actions/{source}/{id}/approve")
    public R<?> approveAction(@PathVariable String source,
                              @PathVariable Long id,
                              @RequestBody(required = false) HelpdeskDtos.ActionDecisionRequest request) {
        return R.ok(approvalService.approve(source, id, withDevActor(request)));
    }

    @PostMapping("/actions/{source}/{id}/reject")
    public R<?> rejectAction(@PathVariable String source,
                             @PathVariable Long id,
                             @RequestBody(required = false) HelpdeskDtos.ActionDecisionRequest request) {
        return R.ok(approvalService.reject(source, id, withDevActor(request)));
    }

    /**
     * 开发期补全操作者身份：无登录态时用统一的 {@link com.omnimerchant.agent.support.DevActor} 兜底。
     * TODO 恢复登录鉴权后，改由 JwtPrincipal 注入真实 actorId，删除此方法。
     */
    private HelpdeskDtos.ActionDecisionRequest withDevActor(HelpdeskDtos.ActionDecisionRequest request) {
        var note = request == null ? null : request.note();
        var actorId = com.omnimerchant.agent.support.DevActor.resolve(request == null ? null : request.actorId());
        return new HelpdeskDtos.ActionDecisionRequest(actorId, note);
    }
}
