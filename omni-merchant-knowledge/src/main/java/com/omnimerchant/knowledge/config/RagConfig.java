package com.omnimerchant.knowledge.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

/**
 * RAG 基础设施配置：EmbeddingModel 与 PgVectorStore。
 *
 * <p>迁移自旧单体 {@code RagConfig}，保留当前已经验证过的参数：
 * OpenAI 兼容 Embedding（对接已有 Qwen Embedding）、HNSW 索引、
 * COSINE_DISTANCE 距离、dimensions、vectorTableName。
 *
 * <p>本阶段不为对齐参考项目而更换 embedding provider。
 */
@Configuration
public class RagConfig {

    /**
     * OpenAI 兼容 Embedding（百炼 Qwen Embedding）。
     */
    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${app.embedding.base-url}") String baseUrl,
            @Value("${app.embedding.api-key}") String apiKey,
            @Value("${app.embedding.model}") String model) {

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .build();

        return OpenAiEmbeddingModel.builder()
                .options(options)
                .build();
    }

    /**
     * PostgreSQL + pgvector 向量库。
     */
    @Bean
    public VectorStore vectorStore(
            EmbeddingModel embeddingModel,

            @Qualifier("pgVectorJdbcTemplate")
            JdbcTemplate jdbcTemplate,

            @Value("${app.pgvector.dimensions}")
            int dimensions,

            @Value("${app.pgvector.table-name}")
            String tableName) {

        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(dimensions)
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName(tableName)
                .build();
    }
}
