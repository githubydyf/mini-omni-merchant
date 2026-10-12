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
 *
 * <p><b>租户隔离</b>：PgVector 走独立 PostgreSQL 数据源，MyBatis 租户插件无法覆盖，
 * 因此这里显式追加 metadata 过滤表达式 {@code (tenant_id == 0 || tenant_id == 当前租户)}，
 * 只召回全局公开语料与当前租户私有语料，杜绝跨租户检索。
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

    /** 按指定 TopK 召回（带租户可见性过滤）。 */
    public List<Document> search(String question, int topK) {

        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(topK)
                .similarityThresholdAll()
                .filterExpression(RagTenantScope.filterExpression())
                .build();

        return vectorStore.similaritySearch(request);
    }
}
