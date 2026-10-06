package com.dyf.miniomnimerchant.dto;

public record HybridSearchResult(

        String id,

        String source,

        Integer chunkIndex,

        /**
         * RRF 最终融合分数
         */
        double rrfScore,

        /**
         * 在 Vector Search 中的排名
         * 没出现则为 null
         */
        Integer vectorRank,

        /**
         * 在 BM25 中的排名
         * 没出现则为 null
         */
        Integer bm25Rank,

        /**
         * 原始 Vector score
         * 仅用于观察，不参与 RRF
         */
        Double vectorScore,

        /**
         * 原始 BM25 score
         * 仅用于观察，不参与 RRF
         */
        Float bm25Score,

        String content
) {
}