package com.omnimerchant.knowledge.service.rerank;

import com.omnimerchant.knowledge.dto.RerankResult;
import com.omnimerchant.knowledge.dto.RetrievalCandidate;

import java.util.List;

/**
 * Reranker 抽象：对候选池做精排。
 */
public interface Reranker {

    /**
     * 对候选池重排并返回最终 TopK。
     *
     * @param question   用户问题
     * @param candidates 召回候选池（已按 Chunk ID 去重）
     * @param topK       最终返回数量
     */
    List<RerankResult> rerank(String question, List<RetrievalCandidate> candidates, int topK);

    /**
     * 与 {@link #rerank} 相同，但同时返回执行模式（是否真正重排 / 是否降级）。
     *
     * <p>需要区分“Reranker 正常但分数低”与“Reranker 降级所以没有真实分数”的调用方
     * （例如证据评估）应使用本方法。默认实现视为正常重排。
     */
    default RerankOutcome rerankWithEvidence(String question,
                                             List<RetrievalCandidate> candidates,
                                             int topK) {
        return new RerankOutcome(rerank(question, candidates, topK), RerankOutcome.MODE_RERANKED);
    }
}
