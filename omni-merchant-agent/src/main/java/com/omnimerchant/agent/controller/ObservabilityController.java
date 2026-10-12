package com.omnimerchant.agent.controller;

import com.omnimerchant.agent.dto.ObservabilityDtos;
import com.omnimerchant.agent.service.AgentTraceService;
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

/**
 * 可观测性接口（Trace 轨迹查询）。
 *
 * <p>复现自参考项目 {@code controller/ObservabilityController} 的 traces 部分，
 * 路径与前端 {@code TracesView.vue} 保持一致：
 * <ul>
 *   <li>{@code GET /api/observability/traces}</li>
 *   <li>{@code GET /api/observability/traces/{traceId}}</li>
 * </ul>
 *
 * <p><b>租户边界</b>：租户来自已验证请求的 {@link TenantContextHolder}
 * （由 {@code TenantInterceptor} 依据 JWT membership 校验后设置），不再信任请求头 / 查询参数；
 * 需要 {@code audit:read} 权限。AgentRun / AgentStep 查询由 MyBatis 租户插件按租户隔离。
 */
@RestController
@RequestMapping("/api/observability")
@RequiredArgsConstructor
public class ObservabilityController {

    private final AgentTraceService agentTraceService;

    @GetMapping("/traces")
    @PreAuthorize("@tenantAuthorization.hasPermission('audit:read')")
    public R<?> traces(@RequestParam(required = false) String conversationUuid,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "20") int size) {
        return R.ok(agentTraceService.listTraces(requireTenant(), conversationUuid, status, page, size));
    }

    @GetMapping("/traces/{traceId}")
    @PreAuthorize("@tenantAuthorization.hasPermission('audit:read')")
    public R<ObservabilityDtos.TraceDetailVO> trace(@PathVariable String traceId) {
        // AgentRun / AgentStep 的查询由租户插件自动追加 tenant_id，跨租户 traceId 不会被命中
        requireTenant();
        var detail = agentTraceService.getTrace(traceId);
        if (detail == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该 traceId 对应的运行轨迹");
        }
        return R.ok(detail);
    }

    private Long requireTenant() {
        var tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING, "缺少租户上下文");
        }
        return tenantId;
    }
}
