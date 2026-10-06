package com.dyf.miniomnimerchant.controller;

import com.dyf.miniomnimerchant.service.AgentOrchestratorService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentOrchestratorService orchestrator;

    public ChatController(
            AgentOrchestratorService orchestrator) {

        this.orchestrator = orchestrator;
    }

    @PostMapping
    public String chat(@RequestBody String message) {

        return orchestrator.chat(message);
    }
}