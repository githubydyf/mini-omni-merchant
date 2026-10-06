package com.omnimerchant.knowledge.controller;

import com.omnimerchant.common.dto.R;
import com.omnimerchant.knowledge.dto.RerankResult;
import com.omnimerchant.knowledge.service.RerankedRagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 检索调试接口（开发期）。
 *
 * <p>用途：在 Agent 接入之前独立验证 RAG 本身是否正确
 * （Vector 有结果、BM25 有结果、候选去重、Reranker 返回结果、TopK 来源正确）。
 * 这样出现问题时可以区分是 RAG 的问题还是 Agent Prompt 的问题。
 *
 * <p>返回最终 TopK：source / chunkIndex / rerankScore / content。
 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagSearchController {

    private final RerankedRagService rerankedRagService;

    /**
     * 示例：{@code GET /api/rag/search?question=退货期限是多少天？}
     */
    @GetMapping("/search")
    public R<List<RerankResult>> search(
            @RequestParam String question,
            @RequestParam(defaultValue = "10") int candidateK,
            @RequestParam(defaultValue = "5") int topK) throws Exception {

        return R.ok(rerankedRagService.search(question, candidateK, topK));
    }
}
