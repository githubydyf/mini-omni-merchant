package com.omnimerchant.knowledge.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量检索（PgVector）。
 *
 * <p>迁移自旧单体同名类，只做 Vector TopK 召回，不做业务编排。
 */
@Service
public class PolicyRagService {

    private final VectorStore vectorStore;

    public PolicyRagService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** 默认召回 5 条。 */
    public List<Document> search(String question) {
        return search(question, 5);
    }

    /** 按指定 TopK 召回。 */
    public List<Document> search(String question, int topK) {

        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(topK)
                .similarityThresholdAll()
                .build();

        return vectorStore.similaritySearch(request);
    }
}
