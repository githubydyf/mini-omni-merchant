package com.omnimerchant.knowledge.dto;

/**
 * RRF 融合后的检索结果。
 *
 * @param id         Chunk 唯一 ID
 * @param source     政策文件名
 * @param chunkIndex 文件内分块序号
 * @param rrfScore   RRF 融合分数
 * @param vectorRank Vector 召回排名（未召回为 null）
 * @param bm25Rank   BM25 召回排名（未召回为 null）
 * @param vectorScore Vector 原始相似度分（仅调试）
 * @param bm25Score   BM25 原始得分（仅调试）
 * @param content    Chunk 正文
 */
public record HybridSearchResult(
        String id,
        String source,
        Integer chunkIndex,
        double rrfScore,
        Integer vectorRank,
        Integer bm25Rank,
        Double vectorScore,
        Float bm25Score,
        String content
) {
}
