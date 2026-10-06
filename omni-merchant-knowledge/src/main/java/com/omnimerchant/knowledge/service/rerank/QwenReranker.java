package com.omnimerchant.knowledge.service.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.omnimerchant.knowledge.dto.RerankResult;
import com.omnimerchant.knowledge.dto.RetrievalCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Comparator;
import java.util.List;

/**
 * 百炼 Qwen Reranker（{@code qwen3.7-text-rerank}）实现。
 *
 * <p>迁移自旧单体同名类，保留已验证过的请求格式。
 *
 * <p><b>本次调整</b>：
 * <ol>
 *   <li>instruct 由英文改为中文（所有面向模型的提示统一中文）。</li>
 *   <li>新增降级：Reranker API 不可用时，不再让整个政策查询失败，
 *       而是退回召回候选池的排名结果，并明确记录错误日志。
 *       降级只返回候选池中的真实片段，不伪造任何政策内容。</li>
 * </ol>
 */
@Slf4j
@Service
public class QwenReranker implements Reranker {

    /** 中文 instruct：引导 Reranker 关注能直接回答问题的政策片段。 */
    private static final String INSTRUCT = """
            给定一个客服政策问题，请优先选择能够直接回答用户问题的政策片段。
            优先考虑与退货、退款、配送、售后条件和时效直接相关的内容，
            不要因为只出现相同关键词就给予过高相关性。
            """.replace("\n", " ").trim();

    private final RestClient restClient;
    private final String url;
    private final String apiKey;
    private final String model;

    public QwenReranker(
            @Value("${app.reranker.url}") String url,
            @Value("${app.reranker.api-key}") String apiKey,
            @Value("${app.reranker.model}") String model) {
        this.url = url;
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = RestClient.builder().build();
    }

    @Override
    public List<RerankResult> rerank(String question, List<RetrievalCandidate> candidates, int topK) {

        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须大于 0");
        }

        List<String> documents = candidates.stream()
                .map(RetrievalCandidate::content)
                .toList();

        // top_n = 候选数量：让 Reranker 返回全部候选评分，再由 Java 截取 TopK
        QwenRerankRequest request = new QwenRerankRequest(
                model,
                new QwenInput(question, documents),
                new QwenParameters(candidates.size(), INSTRUCT));

        try {
            QwenRerankResponse response = restClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(QwenRerankResponse.class);

            if (response == null) {
                throw new IllegalStateException("Reranker 返回 response=null");
            }
            if (response.output() == null || response.output().results() == null) {
                throw new IllegalStateException("Reranker 返回 results=null");
            }

            return response.output().results().stream()
                    // 官方结果已按 relevance_score 降序，这里再排一次以防接口行为变化
                    .sorted(Comparator.comparingDouble(QwenRerankItem::relevanceScore).reversed())
                    .limit(Math.min(topK, candidates.size()))
                    .map(item -> {
                        Integer index = item.index();
                        if (index == null || index < 0 || index >= candidates.size()) {
                            throw new IllegalStateException("Reranker 返回非法 index：" + index);
                        }
                        RetrievalCandidate candidate = candidates.get(index);
                        return new RerankResult(
                                candidate.id(),
                                candidate.source(),
                                candidate.chunkIndex(),
                                item.relevanceScore(),
                                candidate.vectorRank(),
                                candidate.bm25Rank(),
                                candidate.content());
                    })
                    .toList();

        } catch (RestClientResponseException e) {
            log.error("调用 {} 失败：HTTP {}，response={}，url={}",
                    model, e.getStatusCode(), e.getResponseBodyAsString(), url);
            return fallback(candidates, topK, "HTTP " + e.getStatusCode());
        } catch (Exception e) {
            log.error("Reranker 执行失败：{}，url={}，model={}", e.getMessage(), url, model);
            return fallback(candidates, topK, e.getMessage());
        }
    }

    /**
     * 降级：Reranker 不可用时退回召回候选池排名结果。
     *
     * <p>只返回候选池中的真实片段，rerankScore 置 0（表示未经过重排）。
     * 排序规则：优先按 Vector 排名，其次按 BM25 排名。
     */
    private List<RerankResult> fallback(List<RetrievalCandidate> candidates, int topK, String reason) {

        log.warn("Reranker 降级：退回召回候选排名，原因：{}", reason);

        return candidates.stream()
                .sorted(Comparator
                        .comparingInt((RetrievalCandidate c) ->
                                c.vectorRank() == null ? Integer.MAX_VALUE : c.vectorRank())
                        .thenComparingInt(c ->
                                c.bm25Rank() == null ? Integer.MAX_VALUE : c.bm25Rank()))
                .limit(Math.min(topK, candidates.size()))
                .map(candidate -> new RerankResult(
                        candidate.id(),
                        candidate.source(),
                        candidate.chunkIndex(),
                        0.0,
                        candidate.vectorRank(),
                        candidate.bm25Rank(),
                        candidate.content()))
                .toList();
    }

    private record QwenRerankRequest(
            String model,
            QwenInput input,
            QwenParameters parameters) {
    }

    private record QwenInput(
            String query,
            List<String> documents) {
    }

    private record QwenParameters(
            @JsonProperty("top_n") Integer topN,
            String instruct) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QwenRerankResponse(
            QwenOutput output,
            QwenUsage usage,
            @JsonProperty("request_id") String requestId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QwenOutput(
            List<QwenRerankItem> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QwenRerankItem(
            Integer index,
            @JsonProperty("relevance_score") Double relevanceScore) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QwenUsage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("total_tokens") Integer totalTokens) {
    }
}
