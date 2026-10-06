package com.dyf.miniomnimerchant.dto;

public record RerankResult(

        String id,

        String source,

        Integer chunkIndex,

        /**
         * Reranker 最终相关性分数
         */
        double rerankScore,

        /**
         * 原始 Vector Rank
         * 方便调试
         */
        Integer vectorRank,

        /**
         * 原始 BM25 Rank
         * 方便调试
         */
        Integer bm25Rank,

        String content

) {
}