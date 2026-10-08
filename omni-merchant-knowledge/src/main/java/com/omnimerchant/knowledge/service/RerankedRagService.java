package com.omnimerchant.knowledge.service;

import com.omnimerchant.knowledge.dto.RerankResult;
import com.omnimerchant.knowledge.dto.RetrievalCandidate;
import com.omnimerchant.knowledge.service.rerank.RerankOutcome;
import com.omnimerchant.knowledge.service.rerank.Reranker;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 完整 RAG 检索链（面向 PolicyTools 的最终检索入口）。
 *
 * <p>迁移自旧单体同名类：
 *
 * <pre>
 * 问题
 *   ↓
 * Vector TopK + BM25 TopK
 *   ↓
 * CandidateRetrievalService 按 Chunk ID 去重（候选池）
 *   ↓
 * Qwen Reranker 重排
 *   ↓
 * Final TopK
 * </pre>
 *
 * <p>PolicyTools 只调用本类，不自行编排 Vector / BM25 / RRF / Reranker。
 */
@Service
public class RerankedRagService {

    private final CandidateRetrievalService candidateRetrievalService;
    private final Reranker reranker;

    public RerankedRagService(
            CandidateRetrievalService candidateRetrievalService,
            Reranker reranker) {
        this.candidateRetrievalService = candidateRetrievalService;
        this.reranker = reranker;
    }

    /** 默认 Vector Top10 + BM25 Top10 → 去重 → Reranker Top5。 */
    public List<RerankResult> search(String question) throws Exception {
        return search(question, 10, 5);
    }

    /**
     * @param candidateK 召回候选池大小
     * @param finalTopK  Reranker 最终返回数量
     */
    public List<RerankResult> search(String question, int candidateK, int finalTopK) throws Exception {
        return searchWithEvidence(question, candidateK, finalTopK).results();
    }

    /**
     * 检索并返回执行模式（是否真正重排 / 是否降级）。
     *
     * <p>需要判断“证据强度”的调用方（PolicyTools / 证据评估）应使用本方法，
     * 以便区分真实低分与 Reranker 降级。
     */
    public RerankOutcome searchWithEvidence(String question, int candidateK, int finalTopK) throws Exception {

        List<RetrievalCandidate> candidates =
                candidateRetrievalService.retrieve(question, candidateK);

        return reranker.rerankWithEvidence(question, candidates, finalTopK);
    }

    /** 默认参数的便捷入口。 */
    public RerankOutcome searchWithEvidence(String question) throws Exception {
        return searchWithEvidence(question, 10, 5);
    }
}
