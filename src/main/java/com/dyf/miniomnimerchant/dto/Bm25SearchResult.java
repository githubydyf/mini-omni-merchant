package com.dyf.miniomnimerchant.dto;

public record Bm25SearchResult(
        String id,
        String source,
        Integer chunkIndex,
        float score,
        String content
) {
}