package com.omnimerchant.knowledge.controller;

import com.omnimerchant.common.dto.R;
import com.omnimerchant.knowledge.service.PolicyKnowledgeLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库重建接口（开发/运维期）。
 *
 * <p>迁移自旧单体 {@code RagAdminController}，返回体统一使用本项目的 {@link R}。
 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagAdminController {

    private final PolicyKnowledgeLoader policyKnowledgeLoader;

    /** 重新加载全部政策文件（并同步重建 BM25 索引）。 */
    @PostMapping("/reload")
    public R<Map<String, Integer>> reloadAll() {
        return R.ok(policyKnowledgeLoader.reloadAll());
    }

    /** 只重新加载一个政策文件。 */
    @PostMapping("/reload/{fileName}")
    public R<Map<String, Object>> reloadOne(@PathVariable String fileName) {

        int chunkCount = policyKnowledgeLoader.reloadOne(fileName);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file", fileName);
        data.put("chunkCount", chunkCount);
        data.put("message", "知识库更新成功");
        return R.ok(data);
    }
}
