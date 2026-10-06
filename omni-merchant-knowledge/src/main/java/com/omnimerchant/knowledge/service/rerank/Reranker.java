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
}
