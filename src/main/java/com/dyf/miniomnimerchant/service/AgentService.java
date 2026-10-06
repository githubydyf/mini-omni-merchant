package com.dyf.miniomnimerchant.service;

import com.dyf.miniomnimerchant.tool.OrderTools;
import com.dyf.miniomnimerchant.tool.ProductTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class AgentService {

    private final ChatClient chatClient;

    public AgentService(
            ChatClient.Builder builder,
            OrderTools orderTools,
            ProductTools productTools) {

        this.chatClient = builder
                .defaultTools(
                        orderTools,
                        productTools
                )
                .build();
    }

    public String chat(String message) {

        return chatClient
                .prompt()
                .user(message)
                .call()
                .content();
    }
}