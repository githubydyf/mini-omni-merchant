package com.omnimerchant.knowledge.dto;

/**
 * BM25 检索结果。
 *
 * <p>由 {@code Bm25Retriever} 从 Lucene 内存索引返回。{@code id} 与
 * PgVector 中同一 Chunk 的 ID 完全一致，是 Vector / BM25 候选去重的依据。
 *
 * @param id         Chunk 唯一 ID（与 PgVector policy_vectors.id 一致）
 * @param source     政策文件名，例如 refund-policy.txt
 * @param chunkIndex 该文件内的分块序号
 * @param score      Lucene BM25 原始得分（不可与 Vector 余弦分直接相加，融合走 RRF）
 * @param content    Chunk 正文
 */
public record Bm25SearchResult(
        String id,
        String source,
        Integer chunkIndex,
        float score,
        String content
) {
}
