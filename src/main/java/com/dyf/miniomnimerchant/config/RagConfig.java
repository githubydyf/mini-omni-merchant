package com.dyf.miniomnimerchant.config;

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
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

@Configuration
public class RagConfig {

    /**
     * 百炼 Qwen Embedding
     */
    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${app.embedding.base-url}") String baseUrl,
            @Value("${app.embedding.api-key}") String apiKey,
            @Value("${app.embedding.model}") String model) {

        OpenAiEmbeddingOptions options =
                OpenAiEmbeddingOptions.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .model(model)
                        .build();

        return OpenAiEmbeddingModel.builder()
                .options(options)
                .build();
    }


    /**
     * PostgreSQL + pgvector
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

        return PgVectorStore
                .builder(
                        jdbcTemplate,
                        embeddingModel
                )
                .dimensions(dimensions)
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName(tableName)
                .build();
    }
}