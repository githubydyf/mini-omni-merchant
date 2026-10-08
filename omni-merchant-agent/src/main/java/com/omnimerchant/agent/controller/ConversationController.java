package com.omnimerchant.agent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.omnimerchant.agent.dto.ConversationCreateRequest;
import com.omnimerchant.agent.dto.ConversationVO;
import com.omnimerchant.agent.entity.ChatMessage;
import com.omnimerchant.agent.service.ConversationLifecycleService;
import com.omnimerchant.agent.service.ConversationService;
import com.omnimerchant.common.dto.R;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final ConversationLifecycleService conversationLifecycleService;

    @GetMapping
    public R<IPage<ConversationVO>> list(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(conversationService.listConversations(tenantId, status, page, size));
    }

    @GetMapping("/{conversationUuid}")
    public R<ConversationVO> getByUuid(@PathVariable String conversationUuid) {
        return R.ok(conversationService.getByUuid(conversationUuid));
    }

    @GetMapping("/{conversationUuid}/messages")
    public R<List<ChatMessage>> getMessages(@PathVariable String conversationUuid) {
        return R.ok(conversationService.getMessages(conversationUuid));
    }

    /**
     * 真实创建会话（供前端"新对话"）。
     *
     * <p>不复用前端本地生成的 UUID：会话必须在数据库中真实存在，后续消息才能落库、
     * Inbox 才能看到、Tool 审计才能关联。
     */
    @PostMapping
    public R<ConversationVO> create(
            @RequestBody(required = false) ConversationCreateRequest request,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        var body = request == null
                ? new ConversationCreateRequest(null, null, null, null) : request;
        var tenantId = body.tenantId() != null ? body.tenantId() : headerTenantId;
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少租户标识，无法创建会话");
        }
        var conversation = conversationLifecycleService.create(
                tenantId, body.channel(), body.customerEmail(), body.customerName());
        return R.ok(conversationService.getByUuid(conversation.getConversationUuid()));
    }
}
