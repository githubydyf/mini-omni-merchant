package com.omnimerchant.agent.controller;

import com.omnimerchant.agent.dto.AgentChatRequest;
import com.omnimerchant.agent.service.AgentOrchestratorService;
import com.omnimerchant.agent.service.ReActAgentService;
import com.omnimerchant.common.dto.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开发阶段 Agent 测试入口（不是正式 Widget / 渠道入口）。
 *
 * <p>用于直接验证主链：intent → AgentOrchestratorService → SpecialistPlan →
 * Tool 白名单 → DeepSeek → Tool Calling → 中文回答。
 */
@RestController
@RequestMapping("/api/test/agent")
@RequiredArgsConstructor
public class TestAgentController {

    private final ReActAgentService reActAgentService;

    /** 发起一次单轮 Agent 对话。 */
    @PostMapping("/chat")
    public R<String> chat(@RequestBody AgentChatRequest request) {
        var answer = reActAgentService.chat(
                request.conversationUuid(), request.message(), request.intent());
        return R.ok(answer);
    }

    /** 仅查看编排结果（不调用模型），便于验证 SpecialistPlan 与 Tool 白名单。 */
    @GetMapping("/plan")
    public R<AgentOrchestratorService.SpecialistPlan> plan(
            @RequestParam String intent,
            @RequestParam(defaultValue = "") String message) {
        return R.ok(reActAgentService.planFor(intent, message));
    }
}
