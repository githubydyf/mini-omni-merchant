package com.dyf.miniomnimerchant.dto;

public record RetrievalCandidate(

        String id,

        String source,

        Integer chunkIndex,

        String content,

        /**
         * Vector 原始排名
         * 如果 Vector 没有召回，则为 null
         */
        Integer vectorRank,

        /**
         * BM25 原始排名
         * 如果 BM25 没有召回，则为 null
         */
        Integer bm25Rank,

        /**
         * Vector 原始 score
         * 仅调试使用
         */
        Double vectorScore,

        /**
         * BM25 原始 score
         * 仅调试使用
         */
        Double bm25Score

) {
}