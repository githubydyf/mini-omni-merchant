package com.dyf.miniomnimerchant.service;

import com.dyf.miniomnimerchant.dto.RerankResult;
import com.dyf.miniomnimerchant.dto.RetrievalCandidate;
import com.dyf.miniomnimerchant.service.rerank.Reranker;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RerankedRagService {

    private final CandidateRetrievalService
            candidateRetrievalService;

    private final Reranker reranker;


    public RerankedRagService(

            CandidateRetrievalService
                    candidateRetrievalService,

            Reranker reranker
    ) {

        this.candidateRetrievalService =
                candidateRetrievalService;

        this.reranker =
                reranker;
    }


    /**
     * 完整流程：
     *
     * Vector Top candidateK
     * +
     * BM25 Top candidateK
     * ↓
     * ID 去重
     * ↓
     * Candidate Pool
     * ↓
     * Reranker
     * ↓
     * Final TopK
     */
    public List<RerankResult> search(
            String question,
            int candidateK,
            int finalTopK
    ) throws Exception {

        /*
         * ==========================================
         * 1. Recall
         * ==========================================
         */

        List<RetrievalCandidate> candidates =
                candidateRetrievalService
                        .retrieve(
                                question,
                                candidateK
                        );


        /*
         * ==========================================
         * 2. Precision Ranking
         * ==========================================
         */

        return reranker.rerank(
                question,
                candidates,
                finalTopK
        );
    }


    /**
     * 默认：
     *
     * Vector Top10
     * BM25 Top10
     * ↓
     * Union
     * ↓
     * Reranker Top5
     */
    public List<RerankResult> search(
            String question
    ) throws Exception {

        return search(
                question,
                10,
                5
        );
    }
}