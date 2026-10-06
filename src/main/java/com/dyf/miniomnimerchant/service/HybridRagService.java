package com.dyf.miniomnimerchant.service;

import com.dyf.miniomnimerchant.dto.Bm25SearchResult;
import com.dyf.miniomnimerchant.dto.HybridSearchResult;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HybridRagService {

    /**
     * RRF 常用常数
     */
    private static final int RRF_K = 60;

    private static final double VECTOR_WEIGHT = 0.95;

    private static final double BM25_WEIGHT = 0.05;


    private final PolicyRagService policyRagService;

    private final Bm25Retriever bm25Retriever;


    public HybridRagService(
            PolicyRagService policyRagService,
            Bm25Retriever bm25Retriever
    ) {

        this.policyRagService =
                policyRagService;

        this.bm25Retriever =
                bm25Retriever;
    }


    /**
     * Hybrid Search
     *
     * @param question   用户问题
     * @param candidateK 每一路先召回多少个候选
     * @param finalTopK  RRF 最终返回多少个
     */
    public List<HybridSearchResult> search(
            String question,
            int candidateK,
            int finalTopK
    ) throws Exception {

        /*
         * -----------------------------------------
         * 1. Vector Search
         * -----------------------------------------
         */

        List<Document> vectorResults =
                policyRagService.search(
                        question,
                        candidateK
                );


        /*
         * -----------------------------------------
         * 2. BM25 Search
         * -----------------------------------------
         */

        List<Bm25SearchResult> bm25Results =
                bm25Retriever.search(
                        question,
                        candidateK
                );


        /*
         * key:
         *
         * Chunk ID
         *
         * 同一个 Chunk 在 Vector 和 BM25 中
         * 都使用同一个 id 进行合并
         */
        Map<String, FusionCandidate> candidateMap =
                new LinkedHashMap<>();


        /*
         * -----------------------------------------
         * 3. 处理 Vector 排名
         * -----------------------------------------
         */

        for (int i = 0; i < vectorResults.size(); i++) {

            Document document =
                    vectorResults.get(i);

            /*
             * rank 从 1 开始
             */
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


            FusionCandidate candidate =
                    candidateMap.computeIfAbsent(
                            id,
                            key -> new FusionCandidate(
                                    id,
                                    source,
                                    chunkIndex,
                                    document.getText()
                            )
                    );


            /*
             * 记录 Vector 排名
             */
            candidate.vectorRank =
                    rank;


            /*
             * 原始 Vector score
             *
             * 只用于观察
             * 不参与 RRF
             */
            candidate.vectorScore =
                    document.getScore();


            /*
             * RRF：
             *
             * 1 / (60 + rank)
             */

            candidate.rrfScore +=
                    VECTOR_WEIGHT / (RRF_K + rank);
        }


        /*
         * -----------------------------------------
         * 4. 处理 BM25 排名
         * -----------------------------------------
         */

        for (int i = 0; i < bm25Results.size(); i++) {

            Bm25SearchResult result =
                    bm25Results.get(i);


            int rank =
                    i + 1;


            String id =
                    result.id();


            FusionCandidate candidate =
                    candidateMap.computeIfAbsent(
                            id,
                            key -> new FusionCandidate(
                                    result.id(),
                                    result.source(),
                                    result.chunkIndex(),
                                    result.content()
                            )
                    );


            /*
             * 记录 BM25 排名
             */
            candidate.bm25Rank =
                    rank;


            /*
             * BM25 原始 score
             *
             * 只用于观察
             */
            candidate.bm25Score =
                    result.score();


            /*
             * RRF 分数
             */


            candidate.rrfScore +=
                    BM25_WEIGHT / (RRF_K + rank);


        }


        /*
         * -----------------------------------------
         * 5. RRF Score 降序排序
         * -----------------------------------------
         */

        List<FusionCandidate> candidates =
                new ArrayList<>(
                        candidateMap.values()
                );


        candidates.sort(
                Comparator.comparingDouble(
                        (FusionCandidate candidate) ->
                                candidate.rrfScore
                ).reversed()
        );


        /*
         * -----------------------------------------
         * 6. 取最终 TopK
         * -----------------------------------------
         */

        return candidates.stream()
                .limit(finalTopK)
                .map(candidate ->
                        new HybridSearchResult(

                                candidate.id,

                                candidate.source,

                                candidate.chunkIndex,

                                candidate.rrfScore,

                                candidate.vectorRank,

                                candidate.bm25Rank,

                                candidate.vectorScore,

                                candidate.bm25Score,

                                candidate.content
                        )
                )
                .toList();
    }


    /**
     * 默认参数
     * <p>
     * Vector Top10
     * BM25 Top10
     * ↓
     * RRF
     * ↓
     * Final Top5
     */
    public List<HybridSearchResult> search(
            String question
    ) throws Exception {

        return search(
                question,
                10,
                5
        );
    }


    /**
     * metadata 中的 chunk_index
     * 转 Integer
     */
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
     * RRF 内部临时候选对象
     */
    private static class FusionCandidate {

        private final String id;

        private final String source;

        private final Integer chunkIndex;

        private final String content;


        private double rrfScore =
                0.0;


        private Integer vectorRank;

        private Integer bm25Rank;


        private Double vectorScore;

        private Float bm25Score;


        private FusionCandidate(
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