package com.omnimerchant.agent.service;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 执行守卫（本阶段为最小版）。
 *
 * <p>复现自参考项目 {@code service/AgentExecutionGuardService}，但只保留
 * <b>Tool 白名单过滤</b>这一核心能力：
 *
 * <pre>
 * ToolCallbackProvider 提供的全部 ToolCallback
 *         ↓
 * 按 ToolDefinition.name() 建索引
 *         ↓
 * 只保留 plan.toolAllowlist() 中列出的 Tool
 *         ↓
 * 返回给 ChatClient（未允许的 Tool 不会注册到模型，模型根本看不到）
 * </pre>
 *
 * <p>原项目完整版还包含：Redis 会话互斥锁（acquire/release）、
 * GuaredToolCallback 运行时二次校验、副作用工具幂等（agent_idempotency_guard）、
 * 状态机联动。本阶段这些全部暂不实现（依赖 Redis / 幂等表 / 状态机模块）。
 */
@Service
public class AgentExecutionGuardService {

    /**
     * 按 SpecialistPlan 的白名单过滤 ToolCallback。
     *
     * @param callbacks 当前已注册的全部 ToolCallback
     * @param plan      本次请求的编排结果
     * @return 仅包含白名单内 Tool 的回调列表
     * @throws IllegalStateException 白名单配置了当前不存在的 Tool（配置错误，需尽快暴露）
     */
    public List<ToolCallback> guardedCallbacks(ToolCallback[] callbacks,
                                               AgentOrchestratorService.SpecialistPlan plan) {
        var callbackByName = Arrays.stream(callbacks)
                .collect(Collectors.toMap(
                        callback -> callback.getToolDefinition().name(),
                        callback -> callback));
        var missing = plan.toolAllowlist().stream()
                .filter(name -> !callbackByName.containsKey(name))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("配置的 specialist 工具缺失：" + missing);
        }
        return plan.toolAllowlist().stream()
                .map(callbackByName::get)
                .map(callback -> (ToolCallback) callback)
                .toList();
    }



}
