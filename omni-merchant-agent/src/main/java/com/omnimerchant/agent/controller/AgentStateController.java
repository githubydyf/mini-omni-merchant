package com.omnimerchant.agent.controller;

import com.omnimerchant.agent.entity.AgentStateTransition;
import com.omnimerchant.agent.service.AgentStateMachineService;
import com.omnimerchant.common.dto.R;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 会话状态查询（只读接口）。
 *
 * <p><b>租户边界</b>：租户来自已验证请求的 {@link TenantContextHolder}
 * （由 {@code TenantInterceptor} 依据 JWT membership 校验后设置），
 * 不再信任查询参数中的 tenantId；需要 {@code audit:read} 权限。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentStateController {

    private final AgentStateMachineService agentStateMachineService;

    /**
     * 查询某会话的当前状态机状态与最近转换历史。
     *
     * <p>示例：{@code GET /api/agent/state/{conversationUuid}?limit=10}（需带 X-Tenant-Id）
     */
    @GetMapping("/state/{conversationUuid}")
    @PreAuthorize("@tenantAuthorization.hasPermission('audit:read')")
    public R<Map<String, Object>> state(
            @PathVariable String conversationUuid,
            @RequestParam(defaultValue = "10") int limit) {

        var tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING, "缺少租户上下文");
        }

        var state = agentStateMachineService.currentState(tenantId, conversationUuid);
        List<AgentStateTransition> transitions;
        try {
            transitions = agentStateMachineService.recentTransitions(tenantId, conversationUuid, limit);
        } catch (Exception ignored) {
            // 尚无转换历史属正常情况
            transitions = List.of();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationUuid", conversationUuid);
        data.put("tenantId", tenantId);
        data.put("state", state);
        data.put("transitions", transitions);
        return R.ok(data);
    }
}
