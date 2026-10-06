package com.omnimerchant.knowledge.dto;

/**
 * 混合检索的候选片段（Vector 召回 ∪ BM25 召回，按 Chunk ID 去重后）。
 *
 * @param id          Chunk 唯一 ID
 * @param source      政策文件名
 * @param chunkIndex  文件内分块序号
 * @param content     Chunk 正文
 * @param vectorRank  Vector 召回排名（未召回为 null）
 * @param bm25Rank    BM25 召回排名（未召回为 null）
 * @param vectorScore Vector 原始相似度分（仅调试）
 * @param bm25Score   BM25 原始得分（仅调试）
 */
public record RetrievalCandidate(
        String id,
        String source,
        Integer chunkIndex,
        String content,
        Integer vectorRank,
        Integer bm25Rank,
        Double vectorScore,
        Double bm25Score
) {
}
