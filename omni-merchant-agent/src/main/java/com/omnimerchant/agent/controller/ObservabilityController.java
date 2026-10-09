package com.omnimerchant.agent.controller;

import com.omnimerchant.agent.dto.ObservabilityDtos;
import com.omnimerchant.agent.service.AgentTraceService;
import com.omnimerchant.common.dto.R;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可观测性接口（本阶段只实现 Trace 轨迹查询）。
 *
 * <p>复现自参考项目 {@code controller/ObservabilityController} 的 traces 部分，
 * 路径与前端 {@code TracesView.vue} 保持一致：
 * <ul>
 *   <li>{@code GET /api/observability/traces}</li>
 *   <li>{@code GET /api/observability/traces/{traceId}}</li>
 * </ul>
 *
 * <p><b>租户限制（开发期）</b>：当前未复现 JWT/RBAC，{@code X-Tenant-Id} 仅作为开发期
 * 查询过滤条件，<b>不是生产级身份认证</b>。后续 Tenant/Security 阶段再完善访问控制。
 */
@RestController
@RequestMapping("/api/observability")
@RequiredArgsConstructor
public class ObservabilityController {

    private final AgentTraceService agentTraceService;

    @GetMapping("/traces")
    public R<?> traces(@RequestParam(required = false) String conversationUuid,
                       @RequestParam(required = false) String status,
                       @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                       @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "20") int size) {
        return R.ok(agentTraceService.listTraces(tenantId, conversationUuid, status, page, size));
    }

    @GetMapping("/traces/{traceId}")
    public R<ObservabilityDtos.TraceDetailVO> trace(@PathVariable String traceId) {
        var detail = agentTraceService.getTrace(traceId);
        if (detail == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该 traceId 对应的运行轨迹");
        }
        return R.ok(detail);
    }
}
