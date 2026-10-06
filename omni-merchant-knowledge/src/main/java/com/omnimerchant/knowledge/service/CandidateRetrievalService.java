package com.omnimerchant.knowledge.service;

import com.omnimerchant.knowledge.dto.Bm25SearchResult;
import com.omnimerchant.knowledge.dto.RetrievalCandidate;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选召回（Vector TopK + BM25 TopK → 按 Chunk ID 去重）。
 *
 * <p>迁移自旧单体同名类。Vector Top10 + BM25 Top10 去重后通常得到约 15 个候选，
 * 作为 Reranker 的输入候选池。
 *
 * <p>去重依据：Chunk ID。Vector 与 BM25 对同一 Chunk 使用相同唯一 ID。
 */
@Service
public class CandidateRetrievalService {

    private final PolicyRagService policyRagService;
    private final Bm25Retriever bm25Retriever;

    public CandidateRetrievalService(
            PolicyRagService policyRagService,
            Bm25Retriever bm25Retriever) {
        this.policyRagService = policyRagService;
        this.bm25Retriever = bm25Retriever;
    }

    /** 默认 Vector Top10 + BM25 Top10。 */
    public List<RetrievalCandidate> retrieve(String question) throws Exception {
        return retrieve(question, 10);
    }

    /** 按指定 topK 分别召回 Vector 与 BM25，再按 Chunk ID 去重合并。 */
    public List<RetrievalCandidate> retrieve(String question, int topK) throws Exception {

        List<Document> vectorResults = policyRagService.search(question, topK);
        List<Bm25SearchResult> bm25Results = bm25Retriever.search(question, topK);

        // key = Chunk ID，用于去重
        Map<String, CandidateAccumulator> candidateMap = new LinkedHashMap<>();

        for (int i = 0; i < vectorResults.size(); i++) {
            Document document = vectorResults.get(i);
            int rank = i + 1;

            String id = document.getId();
            Map<String, Object> metadata = document.getMetadata();

            String source = String.valueOf(metadata.getOrDefault("source", "unknown"));
            int chunkIndex = parseChunkIndex(metadata.get("chunk_index"));

            String key = buildCandidateKey(id, source, chunkIndex);
            CandidateAccumulator candidate = candidateMap.computeIfAbsent(
                    key,
                    k -> new CandidateAccumulator(id, source, chunkIndex, document.getText()));

            candidate.vectorRank = rank;
            candidate.vectorScore = document.getScore();
        }

        for (int i = 0; i < bm25Results.size(); i++) {
            Bm25SearchResult result = bm25Results.get(i);
            int rank = i + 1;

            String key = buildCandidateKey(result.id(), result.source(), result.chunkIndex());
            CandidateAccumulator candidate = candidateMap.computeIfAbsent(
                    key,
                    k -> new CandidateAccumulator(
                            result.id(), result.source(), result.chunkIndex(), result.content()));

            candidate.bm25Rank = rank;
            candidate.bm25Score = (double) result.score();
        }

        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (CandidateAccumulator candidate : candidateMap.values()) {
            candidates.add(new RetrievalCandidate(
                    candidate.id,
                    candidate.source,
                    candidate.chunkIndex,
                    candidate.content,
                    candidate.vectorRank,
                    candidate.bm25Rank,
                    candidate.vectorScore,
                    candidate.bm25Score
            ));
        }

        return candidates;
    }

    /** 优先使用 Chunk ID；极端情况下退化为 source#chunkIndex。 */
    private String buildCandidateKey(String id, String source, Integer chunkIndex) {
        if (id != null && !id.isBlank()) {
            return id;
        }
        return source + "#" + chunkIndex;
    }

    private int parseChunkIndex(Object value) {
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 内部可变对象：Vector / BM25 会逐步补充 rank 与 score。
     */
    private static class CandidateAccumulator {

        private final String id;
        private final String source;
        private final Integer chunkIndex;
        private final String content;

        private Integer vectorRank;
        private Integer bm25Rank;
        private Double vectorScore;
        private Double bm25Score;

        private CandidateAccumulator(String id, String source, Integer chunkIndex, String content) {
            this.id = id;
            this.source = source;
            this.chunkIndex = chunkIndex;
            this.content = content;
        }
    }
}
