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
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话查询与创建接口。
 *
 * <p><b>租户边界</b>：租户一律来自已验证请求的 {@link TenantContextHolder}
 * （{@code TenantInterceptor} 校验 JWT membership 后设置），不再信任查询参数 / 请求体中的
 * tenantId；{@code conversation} / {@code chat_message} 查询由 MyBatis 租户插件自动隔离，
 * 因此 {@code GET /api/conversations/{uuid}} 与 {@code /messages} 无法跨租户读取。
 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final ConversationLifecycleService conversationLifecycleService;

    @GetMapping
    public R<IPage<ConversationVO>> list(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 租户由拦截器写入上下文，这里只做会话状态过滤；租户过滤由 SQL 插件自动追加
        return R.ok(conversationService.listConversations(requireTenant(), status, page, size));
    }

    @GetMapping("/{conversationUuid}")
    public R<ConversationVO> getByUuid(@PathVariable String conversationUuid) {
        requireTenant();
        return R.ok(conversationService.getByUuid(conversationUuid));
    }

    @GetMapping("/{conversationUuid}/messages")
    public R<List<ChatMessage>> getMessages(@PathVariable String conversationUuid) {
        requireTenant();
        return R.ok(conversationService.getMessages(conversationUuid));
    }

    /**
     * 真实创建会话（供前端"新对话"）。
     *
     * <p>不复用前端本地生成的 UUID：会话必须在数据库中真实存在，后续消息才能落库、
     * Inbox 才能看到、Tool 审计才能关联。租户来自已验证上下文，请求体中的 tenantId 被忽略。
     */
    @PostMapping
    public R<ConversationVO> create(@RequestBody(required = false) ConversationCreateRequest request) {
        var body = request == null
                ? new ConversationCreateRequest(null, null, null, null) : request;
        var conversation = conversationLifecycleService.create(
                requireTenant(), body.channel(), body.customerEmail(), body.customerName());
        return R.ok(conversationService.getByUuid(conversation.getConversationUuid()));
    }

    private Long requireTenant() {
        var tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING, "缺少租户上下文");
        }
        return tenantId;
    }
}
