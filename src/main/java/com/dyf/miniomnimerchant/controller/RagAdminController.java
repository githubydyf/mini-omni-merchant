package com.dyf.miniomnimerchant.controller;

import com.dyf.miniomnimerchant.service.PolicyKnowledgeLoader;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagAdminController {

    private final PolicyKnowledgeLoader
            policyKnowledgeLoader;

    public RagAdminController(
            PolicyKnowledgeLoader policyKnowledgeLoader) {

        this.policyKnowledgeLoader =
                policyKnowledgeLoader;
    }


    /**
     * 重新加载全部政策文件
     */
    @PostMapping("/reload")
    public Map<String, Integer> reloadAll() {

        return policyKnowledgeLoader
                .reloadAll();
    }


    /**
     * 只重新加载一个政策文件
     */
    @PostMapping("/reload/{fileName}")
    public Map<String, Object> reloadOne(
            @PathVariable String fileName) {

        int chunkCount =
                policyKnowledgeLoader
                        .reloadOne(fileName);

        return Map.of(
                "file", fileName,
                "chunkCount", chunkCount,
                "message", "知识库更新成功"
        );
    }
}