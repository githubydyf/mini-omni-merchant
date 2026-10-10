package com.omnimerchant.agent.controller;

import com.omnimerchant.agent.entity.AgentStateTransition;
import com.omnimerchant.agent.service.AgentStateMachineService;
import com.omnimerchant.common.dto.R;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 会话状态查询（开发期只读接口）。
 *
 * <p><b>租户限制（开发期）</b>：当前未复现 JWT/RBAC，{@code X-Tenant-Id} 仅作为开发期
 * 查询过滤条件，<b>不是生产级身份认证</b>，不对公网开放。后续 Tenant/Security 阶段再完善。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentStateController {

    private final AgentStateMachineService agentStateMachineService;

    /**
     * 查询某会话的当前状态机状态与最近转换历史。
     *
     * <p>示例：{@code GET /api/agent/state/{conversationUuid}?tenantId=1001}
     */
    @GetMapping("/state/{conversationUuid}")
    public R<Map<String, Object>> state(
            @PathVariable String conversationUuid,
            @RequestParam(required = false) Long tenantId,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId,
            @RequestParam(defaultValue = "10") int limit) {

        // 当前只有开发期租户标识：状态机 requireTenant 会校验租户上下文一致性
        var effectiveTenantId = tenantId != null ? tenantId : headerTenantId;
        if (effectiveTenantId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少租户标识，无法查询会话状态");
        }

        // 只读查询同样要经过 requireTenant 校验，因此在请求线程显式绑定租户上下文，
        // 并在 finally 中恢复，避免线程池复用造成上下文串扰。
        var previousTenant = TenantContextHolder.get();
        TenantContextHolder.set(effectiveTenantId);
        try {
            var state = agentStateMachineService.currentState(effectiveTenantId, conversationUuid);
            List<AgentStateTransition> transitions;
            try {
                transitions = agentStateMachineService.recentTransitions(
                        effectiveTenantId, conversationUuid, limit);
            } catch (Exception ignored) {
                // 尚无转换历史属正常情况
                transitions = List.of();
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("conversationUuid", conversationUuid);
            data.put("tenantId", effectiveTenantId);
            data.put("state", state);
            data.put("transitions", transitions);
            return R.ok(data);
        } finally {
            if (previousTenant == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.set(previousTenant);
            }
        }
    }
}
