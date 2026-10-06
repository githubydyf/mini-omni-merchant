package com.omnimerchant.knowledge.service;

import com.omnimerchant.knowledge.dto.Bm25SearchResult;
import com.omnimerchant.knowledge.dto.HybridSearchResult;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索 + RRF 融合。
 *
 * <p>迁移自旧单体同名类，保留已验证的融合策略：
 *
 * <pre>
 * 用户问题
 *   ↓
 * Vector TopK + BM25 TopK
 *   ↓
 * 按 Chunk ID 合并
 *   ↓
 * RRF 加权融合（Vector 权重 0.95 / BM25 权重 0.05）
 *   ↓
 * 最终 TopK
 * </pre>
 *
 * <p>本类提供“不需要 Reranker 的融合结果”；需要 Reranker 时由
 * {@code RerankedRagService} 使用 {@code CandidateRetrievalService} 走重排链。
 */
@Service
public class HybridRagService {

    /** RRF 常用常数。 */
    private static final int RRF_K = 60;
    private static final double VECTOR_WEIGHT = 0.95;
    private static final double BM25_WEIGHT = 0.05;

    private final PolicyRagService policyRagService;
    private final Bm25Retriever bm25Retriever;

    public HybridRagService(
            PolicyRagService policyRagService,
            Bm25Retriever bm25Retriever) {
        this.policyRagService = policyRagService;
        this.bm25Retriever = bm25Retriever;
    }

    /** 默认 Vector Top10 + BM25 Top10 → RRF → Final Top5。 */
    public List<HybridSearchResult> search(String question) throws Exception {
        return search(question, 10, 5);
    }

    /**
     * @param candidateK 每一路先召回多少个候选
     * @param finalTopK  RRF 最终返回多少个
     */
    public List<HybridSearchResult> search(String question, int candidateK, int finalTopK) throws Exception {

        List<Document> vectorResults = policyRagService.search(question, candidateK);
        List<Bm25SearchResult> bm25Results = bm25Retriever.search(question, candidateK);

        // key = Chunk ID：Vector 与 BM25 使用相同 id 进行合并
        Map<String, FusionCandidate> candidateMap = new LinkedHashMap<>();

        for (int i = 0; i < vectorResults.size(); i++) {
            Document document = vectorResults.get(i);
            int rank = i + 1; // rank 从 1 开始

            String id = document.getId();
            Map<String, Object> metadata = document.getMetadata();
            String source = String.valueOf(metadata.getOrDefault("source", "unknown"));
            int chunkIndex = parseChunkIndex(metadata.get("chunk_index"));

            FusionCandidate candidate = candidateMap.computeIfAbsent(
                    id,
                    key -> new FusionCandidate(id, source, chunkIndex, document.getText()));

            candidate.vectorRank = rank;
            candidate.vectorScore = document.getScore();
            candidate.rrfScore += VECTOR_WEIGHT / (RRF_K + rank);
        }

        for (int i = 0; i < bm25Results.size(); i++) {
            Bm25SearchResult result = bm25Results.get(i);
            int rank = i + 1;

            String id = result.id();
            FusionCandidate candidate = candidateMap.computeIfAbsent(
                    id,
                    key -> new FusionCandidate(
                            result.id(), result.source(), result.chunkIndex(), result.content()));

            candidate.bm25Rank = rank;
            candidate.bm25Score = result.score();
            candidate.rrfScore += BM25_WEIGHT / (RRF_K + rank);
        }

        List<FusionCandidate> candidates = new ArrayList<>(candidateMap.values());
        candidates.sort(Comparator.comparingDouble(
                (FusionCandidate candidate) -> candidate.rrfScore).reversed());

        return candidates.stream()
                .limit(finalTopK)
                .map(candidate -> new HybridSearchResult(
                        candidate.id,
                        candidate.source,
                        candidate.chunkIndex,
                        candidate.rrfScore,
                        candidate.vectorRank,
                        candidate.bm25Rank,
                        candidate.vectorScore,
                        candidate.bm25Score,
                        candidate.content
                ))
                .toList();
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

    /** RRF 内部临时候选对象。 */
    private static class FusionCandidate {

        private final String id;
        private final String source;
        private final Integer chunkIndex;
        private final String content;

        private double rrfScore = 0.0;
        private Integer vectorRank;
        private Integer bm25Rank;
        private Double vectorScore;
        private Float bm25Score;

        private FusionCandidate(String id, String source, Integer chunkIndex, String content) {
            this.id = id;
            this.source = source;
            this.chunkIndex = chunkIndex;
            this.content = content;
        }
    }
}
