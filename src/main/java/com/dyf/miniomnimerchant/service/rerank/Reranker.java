package com.dyf.miniomnimerchant.service.rerank;

import com.dyf.miniomnimerchant.dto.RerankResult;
import com.dyf.miniomnimerchant.dto.RetrievalCandidate;

import java.util.List;

public interface Reranker {

    List<RerankResult> rerank(
            String question,
            List<RetrievalCandidate> candidates,
            int topK
    );
}