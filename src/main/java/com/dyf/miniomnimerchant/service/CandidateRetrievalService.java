package com.dyf.miniomnimerchant.service;

import com.dyf.miniomnimerchant.dto.Bm25SearchResult;
import com.dyf.miniomnimerchant.dto.RetrievalCandidate;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CandidateRetrievalService {

    private final PolicyRagService policyRagService;

    private final Bm25Retriever bm25Retriever;


    public CandidateRetrievalService(
            PolicyRagService policyRagService,
            Bm25Retriever bm25Retriever
    ) {

        this.policyRagService =
                policyRagService;

        this.bm25Retriever =
                bm25Retriever;
    }


    /**
     * Hybrid Candidate Retrieval
     * <p>
     * Vector TopK
     * +
     * BM25 TopK
     * ↓
     * 按 Chunk ID 去重
     * <p>
     * 例如：
     * <p>
     * Vector Top10
     * BM25 Top10
     * <p>
     * 最终通常得到约 15 个 Candidate
     */
    public List<RetrievalCandidate> retrieve(
            String question,
            int topK
    ) throws Exception {

        /*
         * ==========================================
         * 1. Vector TopK
         * ==========================================
         */

        List<Document> vectorResults =
                policyRagService.search(
                        question,
                        topK
                );


        /*
         * ==========================================
         * 2. BM25 TopK
         * ==========================================
         */

        List<Bm25SearchResult> bm25Results =
                bm25Retriever.search(
                        question,
                        topK
                );


        /*
         * LinkedHashMap：
         *
         * key   = Chunk ID
         * value = Candidate
         *
         * 用 ID 去重
         */
        Map<String, CandidateAccumulator> candidateMap =
                new LinkedHashMap<>();


        /*
         * ==========================================
         * 3. 加入 Vector 结果
         * ==========================================
         */

        for (int i = 0;
             i < vectorResults.size();
             i++) {

            Document document =
                    vectorResults.get(i);


            int rank =
                    i + 1;


            String id =
                    document.getId();


            Map<String, Object> metadata =
                    document.getMetadata();


            String source =
                    String.valueOf(
                            metadata.getOrDefault(
                                    "source",
                                    "unknown"
                            )
                    );


            int chunkIndex =
                    parseChunkIndex(
                            metadata.get(
                                    "chunk_index"
                            )
                    );


            String key =
                    buildCandidateKey(
                            id,
                            source,
                            chunkIndex
                    );


            CandidateAccumulator candidate =
                    candidateMap.computeIfAbsent(
                            key,
                            k -> new CandidateAccumulator(
                                    id,
                                    source,
                                    chunkIndex,
                                    document.getText()
                            )
                    );


            candidate.vectorRank =
                    rank;


            candidate.vectorScore =
                    document.getScore();
        }


        /*
         * ==========================================
         * 4. 加入 BM25 结果
         * ==========================================
         */

        for (int i = 0; i < bm25Results.size(); i++) {

            Bm25SearchResult result = bm25Results.get(i);


            int rank = i + 1;


            String key = buildCandidateKey(result.id(), result.source(), result.chunkIndex());


            CandidateAccumulator candidate =
                    candidateMap.computeIfAbsent(
                            key,
                            k -> new CandidateAccumulator(
                                    result.id(),
                                    result.source(),
                                    result.chunkIndex(),
                                    result.content()
                            )
                    );


            candidate.bm25Rank =
                    rank;


            candidate.bm25Score =
                    (double) result.score();
        }


        /*
         * ==========================================
         * 5. 转 DTO
         * ==========================================
         */

        List<RetrievalCandidate> candidates =
                new ArrayList<>();


        for (CandidateAccumulator candidate :
                candidateMap.values()) {

            candidates.add(
                    new RetrievalCandidate(

                            candidate.id,

                            candidate.source,

                            candidate.chunkIndex,

                            candidate.content,

                            candidate.vectorRank,

                            candidate.bm25Rank,

                            candidate.vectorScore,

                            candidate.bm25Score
                    )
            );
        }


        return candidates;
    }


    /**
     * 默认：
     * <p>
     * Vector Top10
     * +
     * BM25 Top10
     */
    public List<RetrievalCandidate> retrieve(
            String question
    ) throws Exception {

        return retrieve(
                question,
                10
        );
    }


    private String buildCandidateKey(
            String id,
            String source,
            Integer chunkIndex
    ) {

        if (id != null &&
                !id.isBlank()) {

            return id;
        }


        /*
         * 正常情况下不会进入这里，
         * 因为你已经确认 Vector/BM25 ID 一致。
         */
        return source
                + "#"
                + chunkIndex;
    }


    private int parseChunkIndex(
            Object value
    ) {

        if (value == null) {
            return -1;
        }

        try {

            return Integer.parseInt(
                    value.toString()
            );

        } catch (Exception e) {

            return -1;
        }
    }


    /**
     * 内部临时对象。
     * <p>
     * 因为 Vector 和 BM25 会逐步补充 rank/score，
     * 所以这里使用可变对象。
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


        private CandidateAccumulator(
                String id,
                String source,
                Integer chunkIndex,
                String content
        ) {

            this.id =
                    id;

            this.source =
                    source;

            this.chunkIndex =
                    chunkIndex;

            this.content =
                    content;
        }
    }
}