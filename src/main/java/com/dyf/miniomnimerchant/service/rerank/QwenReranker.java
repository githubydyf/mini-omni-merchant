package com.dyf.miniomnimerchant.service.rerank;

import com.dyf.miniomnimerchant.dto.RerankResult;
import com.dyf.miniomnimerchant.dto.RetrievalCandidate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Comparator;
import java.util.List;

@Service
public class QwenReranker implements Reranker {

    /**
     * Spring RestClient
     */
    private final RestClient restClient;

    /**
     * Reranker 完整请求地址
     */
    private final String url;

    /**
     * 百炼 API Key
     */
    private final String apiKey;

    /**
     * 模型：
     *
     * qwen3.7-text-rerank
     */
    private final String model;


    public QwenReranker(

            @Value("${app.reranker.url}")
            String url,

            @Value("${app.reranker.api-key}")
            String apiKey,

            @Value("${app.reranker.model}")
            String model
    ) {

        this.url = url;
        this.apiKey = apiKey;
        this.model = model;

        this.restClient =
                RestClient.builder()
                        .build();
    }


    /**
     * ========================================================
     * Rerank
     * ========================================================
     *
     * 输入：
     *
     * question
     *
     * +
     *
     * Vector Top10 + BM25 Top10
     * 去重后的 Candidate Pool
     *
     * ↓
     *
     * qwen3.7-text-rerank
     *
     * ↓
     *
     * Final TopK
     */
    @Override
    public List<RerankResult> rerank(
            String question,
            List<RetrievalCandidate> candidates,
            int topK
    ) {

        /*
         * ----------------------------------------------------
         * 0. 参数检查
         * ----------------------------------------------------
         */

        if (question == null ||
                question.isBlank()) {

            throw new IllegalArgumentException(
                    "question 不能为空"
            );
        }


        if (candidates == null ||
                candidates.isEmpty()) {

            return List.of();
        }


        if (topK <= 0) {

            throw new IllegalArgumentException(
                    "topK 必须大于 0"
            );
        }


        /*
         * ----------------------------------------------------
         * 1. Candidate → documents
         * ----------------------------------------------------
         *
         * documents 的顺序非常重要。
         *
         * Reranker 返回：
         *
         * index = 0
         *
         * 就表示：
         *
         * documents.get(0)
         *
         * 同时也是：
         *
         * candidates.get(0)
         */

        List<String> documents =
                candidates.stream()

                        .map(
                                RetrievalCandidate::content
                        )

                        .toList();


        /*
         * ----------------------------------------------------
         * 2. 构造 qwen3.7-text-rerank 请求
         * ----------------------------------------------------
         *
         * JSON 实际会变成：
         *
         * {
         *   "model": "qwen3.7-text-rerank",
         *
         *   "input": {
         *      "query": "...",
         *      "documents": [...]
         *   },
         *
         *   "parameters": {
         *      "top_n": 15,
         *      "instruct": "..."
         *   }
         * }
         *
         *
         * 这里 top_n = candidates.size()
         *
         * 原因：
         *
         * 让 Reranker 返回所有候选的评分，
         * 然后 Java 再截取最终 TopK。
         *
         * 这样后续调试更方便。
         */

        QwenRerankRequest request =
                new QwenRerankRequest(

                        model,

                        new QwenInput(
                                question,
                                documents
                        ),

                        new QwenParameters(

                                candidates.size(),

                                """
                                Given a customer service policy question, \
                                retrieve passages that directly answer \
                                the user's question.
                                """
                                        .replace(
                                                "\n",
                                                " "
                                        )
                                        .trim()
                        )
                );


        try {

            /*
             * ------------------------------------------------
             * 3. 调用百炼 qwen3.7-text-rerank
             * ------------------------------------------------
             */

            QwenRerankResponse response =
                    restClient

                            .post()

                            .uri(url)

                            .header(
                                    HttpHeaders.AUTHORIZATION,
                                    "Bearer " + apiKey
                            )

                            .contentType(
                                    MediaType.APPLICATION_JSON
                            )

                            .body(request)

                            .retrieve()

                            .body(
                                    QwenRerankResponse.class
                            );


            /*
             * ------------------------------------------------
             * 4. 响应检查
             * ------------------------------------------------
             *
             * qwen3.7-text-rerank：
             *
             * response
             *    ↓
             * output
             *    ↓
             * results
             */

            if (response == null) {

                throw new IllegalStateException(
                        "Reranker 返回 response=null"
                );
            }


            if (response.output() == null) {

                throw new IllegalStateException(
                        "Reranker 返回 output=null"
                );
            }


            if (response.output().results() == null) {

                throw new IllegalStateException(
                        "Reranker 返回 results=null"
                );
            }


            /*
             * ------------------------------------------------
             * 5. Reranker index → 原 Candidate
             * ------------------------------------------------
             */

            return response
                    .output()
                    .results()
                    .stream()

                    /*
                     * 官方结果本身已经按照
                     * relevance_score 从高到低排序。
                     *
                     * 这里再排序一次，
                     * 防止以后接口行为变化。
                     */
                    .sorted(
                            Comparator.comparingDouble(
                                    QwenRerankItem::relevanceScore
                            ).reversed()
                    )

                    /*
                     * 最终只保留 TopK
                     */
                    .limit(
                            Math.min(
                                    topK,
                                    candidates.size()
                            )
                    )

                    /*
                     * 根据 index 找回原 Candidate
                     */
                    .map(item -> {

                        Integer index =
                                item.index();


                        /*
                         * 防止 API 返回非法 index
                         */
                        if (index == null ||
                                index < 0 ||
                                index >= candidates.size()) {

                            throw new IllegalStateException(
                                    "Reranker 返回非法 index："
                                            + index
                            );
                        }


                        RetrievalCandidate candidate =
                                candidates.get(
                                        index
                                );


                        return new RerankResult(

                                candidate.id(),

                                candidate.source(),

                                candidate.chunkIndex(),

                                /*
                                 * Reranker relevance score
                                 */
                                item.relevanceScore(),

                                /*
                                 * 保留原来的 Vector Rank
                                 * 方便调试
                                 */
                                candidate.vectorRank(),

                                /*
                                 * 保留原来的 BM25 Rank
                                 */
                                candidate.bm25Rank(),

                                candidate.content()
                        );
                    })

                    .toList();


        } catch (RestClientResponseException e) {

            /*
             * ------------------------------------------------
             * HTTP 4xx / 5xx
             * ------------------------------------------------
             *
             * 这里把百炼原始响应打出来，
             * 排查问题非常有用。
             */

            throw new IllegalStateException(

                    "调用 qwen3.7-text-rerank 失败"
                            + "\nHTTP Status: "
                            + e.getStatusCode()
                            + "\nResponse: "
                            + e.getResponseBodyAsString()
                            + "\nURL: "
                            + url
                            + "\nModel: "
                            + model,

                    e
            );

        } catch (Exception e) {

            /*
             * 非 HTTP 异常
             */
            throw new IllegalStateException(

                    "Reranker 执行失败"
                            + "\nURL: "
                            + url
                            + "\nModel: "
                            + model,

                    e
            );
        }
    }


    /*
     * ========================================================
     *
     * Request DTO
     *
     * ========================================================
     *
     * qwen3.7-text-rerank 请求结构：
     *
     * {
     *
     *   "model": "...",
     *
     *   "input": {
     *      "query": "...",
     *      "documents": [...]
     *   },
     *
     *   "parameters": {
     *      "top_n": 5,
     *      "instruct": "..."
     *   }
     * }
     *
     */

    private record QwenRerankRequest(

            String model,

            QwenInput input,

            QwenParameters parameters

    ) {
    }


    /**
     * input
     */
    private record QwenInput(

            String query,

            List<String> documents

    ) {
    }


    /**
     * parameters
     */
    private record QwenParameters(

            @JsonProperty("top_n")
            Integer topN,

            String instruct

    ) {
    }


    /*
     * ========================================================
     *
     * Response DTO
     *
     * ========================================================
     *
     * qwen3.7-text-rerank 返回：
     *
     * {
     *
     *   "output": {
     *
     *      "results": [
     *
     *          {
     *              "index": 0,
     *              "relevance_score": 0.93
     *          }
     *
     *      ]
     *   },
     *
     *   "usage": {
     *      ...
     *   },
     *
     *   "request_id": "..."
     * }
     *
     */

    @JsonIgnoreProperties(
            ignoreUnknown = true
    )
    private record QwenRerankResponse(

            QwenOutput output,

            QwenUsage usage,

            @JsonProperty("request_id")
            String requestId

    ) {
    }


    /**
     * output
     */
    @JsonIgnoreProperties(
            ignoreUnknown = true
    )
    private record QwenOutput(

            List<QwenRerankItem> results

    ) {
    }


    /**
     * 单个 Rerank 结果
     */
    @JsonIgnoreProperties(
            ignoreUnknown = true
    )
    private record QwenRerankItem(

            Integer index,

            @JsonProperty(
                    "relevance_score"
            )
            Double relevanceScore

    ) {
    }


    /**
     * Token 使用情况
     *
     * qwen3.7-text-rerank 会返回：
     *
     * prompt_tokens
     * total_tokens
     */
    @JsonIgnoreProperties(
            ignoreUnknown = true
    )
    private record QwenUsage(

            @JsonProperty(
                    "prompt_tokens"
            )
            Integer promptTokens,

            @JsonProperty(
                    "total_tokens"
            )
            Integer totalTokens

    ) {
    }
}