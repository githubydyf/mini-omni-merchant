package com.omnimerchant.knowledge.dto;

/**
 * Reranker 重排后的最终检索结果（RAG 检索链的最终输出单元）。
 *
 * @param id          Chunk 唯一 ID
 * @param source      政策文件名
 * @param chunkIndex  文件内分块序号
 * @param rerankScore Reranker 相关性分数（Reranker 降级时为 0）
 * @param vectorRank  召回阶段 Vector 排名（仅调试）
 * @param bm25Rank    召回阶段 BM25 排名（仅调试）
 * @param content     Chunk 正文
 */
public record RerankResult(
        String id,
        String source,
        Integer chunkIndex,
        double rerankScore,
        Integer vectorRank,
        Integer bm25Rank,
        String content
) {
}
