package com.omnimerchant.knowledge.controller;

import com.omnimerchant.common.dto.R;
import com.omnimerchant.knowledge.dto.RerankResult;
import com.omnimerchant.knowledge.service.RerankedRagService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 检索调试接口（运维期）。
 *
 * <p>用途：在 Agent 接入之前独立验证 RAG 本身是否正确
 * （Vector 有结果、BM25 有结果、候选去重、Reranker 返回结果、TopK 来源正确）。
 * 这样出现问题时可以区分是 RAG 的问题还是 Agent Prompt 的问题。
 *
 * <p><b>安全</b>：调试接口不能默认公开。仅限平台管理员调用，避免匿名用户探测知识库内容。
 * 检索路径本身仍按 {@code tenant_id ∈ {全局, 当前租户}} 过滤（本接口无租户上下文，
 * 故只返回全局演示语料）。
 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
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
