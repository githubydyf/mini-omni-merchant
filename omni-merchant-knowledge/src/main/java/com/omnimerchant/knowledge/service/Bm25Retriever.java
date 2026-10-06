package com.omnimerchant.knowledge.service;

import com.omnimerchant.knowledge.dto.Bm25SearchResult;
import jakarta.annotation.PostConstruct;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * BM25 全文检索（Lucene 内存索引）。
 *
 * <p>迁移自旧单体同名类，保留：
 * <ul>
 *   <li>SmartChineseAnalyzer 中文分词</li>
 *   <li>BM25Similarity 打分</li>
 *   <li>数据来源：PostgreSQL {@code policy_vectors}（与 PgVector 同表）</li>
 *   <li>Chunk 唯一 ID 与 PgVector 完全一致，供候选去重使用</li>
 * </ul>
 *
 * <p>一致性约定：知识库 reload 完成后必须调用 {@link #rebuildIndex()}，
 * 否则会出现“PgVector 已更新但 BM25 仍是旧索引”的问题。
 */
@Slf4j
@Component
public class Bm25Retriever {

    private final JdbcTemplate jdbcTemplate;

    /** PgVector 表名（与 PgVectorStore 配置保持一致）。 */
    private final String tableName;

    /** 中文分词器。 */
    private final Analyzer analyzer = new SmartChineseAnalyzer();

    /** Lucene 内存索引。 */
    private Directory directory;
    private DirectoryReader reader;
    private IndexSearcher searcher;

    public Bm25Retriever(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Value("${app.pgvector.table-name:policy_vectors}") String tableName) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = tableName;
    }

    /**
     * 启动后尝试建立 BM25 索引。
     *
     * <p>PostgreSQL 尚未就绪或政策库为空时不阻断应用启动，只记录日志；
     * 后续可通过知识库 reload 触发 {@link #rebuildIndex()} 重建。
     */
    @PostConstruct
    public void init() {
        try {
            rebuildIndex();
        } catch (Exception e) {
            log.warn("BM25 索引初始化未完成（PostgreSQL 未就绪或政策库为空）：{}", e.getMessage());
        }
    }

    /**
     * 从 PostgreSQL 重新建立 BM25 索引。
     */
    public synchronized void rebuildIndex() throws Exception {

        log.info("开始构建 BM25 索引，数据来源：{}", tableName);

        String sql = """
                SELECT
                    id,
                    content,
                    metadata ->> 'source' AS source,
                    metadata ->> 'chunk_index' AS chunkIndex
                FROM %s
                """.formatted(tableName);

        List<PolicyChunk> chunks = jdbcTemplate.query(sql, (rs, rowNum) -> {
            String id = rs.getString("id");
            String content = rs.getString("content");
            String source = rs.getString("source");
            String chunkIndex = rs.getString("chunkIndex");

            if (source == null) {
                source = "unknown";
            }
            if (chunkIndex == null) {
                chunkIndex = "-1";
            }
            return new PolicyChunk(id, source, chunkIndex, content);
        });

        log.info("从 {} 读取 Chunk 数量：{}", tableName, chunks.size());

        Directory newDirectory = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        // 使用 BM25（默认 k1=1.2, b=0.75）
        config.setSimilarity(new BM25Similarity());

        try (IndexWriter writer = new IndexWriter(newDirectory, config)) {
            for (PolicyChunk chunk : chunks) {
                org.apache.lucene.document.Document luceneDocument = new org.apache.lucene.document.Document();

                // Chunk 唯一 ID：与 PgVector 一致，是候选去重的关键
                luceneDocument.add(new StringField("id", chunk.id(), Field.Store.YES));
                luceneDocument.add(new StringField("source", chunk.source(), Field.Store.YES));
                luceneDocument.add(new StringField("chunkIndex", chunk.chunkIndex(), Field.Store.YES));
                // TextField 经 SmartChineseAnalyzer 分词，BM25 检索该字段
                luceneDocument.add(new TextField("content", chunk.content(), Field.Store.YES));

                writer.addDocument(luceneDocument);
            }
        }

        DirectoryReader newReader = DirectoryReader.open(newDirectory);
        IndexSearcher newSearcher = new IndexSearcher(newReader);
        newSearcher.setSimilarity(new BM25Similarity());

        // 关闭并替换旧索引，避免查询期间出现新旧混用
        if (this.reader != null) {
            this.reader.close();
        }
        if (this.directory != null) {
            this.directory.close();
        }

        this.directory = newDirectory;
        this.reader = newReader;
        this.searcher = newSearcher;

        log.info("BM25 索引构建完成，文档数：{}", newReader.numDocs());
    }

    /**
     * BM25 查询。
     *
     * <p>索引未就绪或参数非法时返回空列表（由上层决定是否视为“无证据”）。
     */
    public List<Bm25SearchResult> search(String question, int topK) throws Exception {

        if (question == null || question.isBlank()) {
            return List.of();
        }
        if (topK <= 0) {
            return List.of();
        }
        if (searcher == null) {
            log.warn("BM25 索引尚未初始化，本次检索返回空结果");
            return List.of();
        }

        QueryParser parser = new QueryParser("content", analyzer);
        // 转义 Lucene 特殊语法（+ - : ( ) 等），防止用户输入导致解析异常
        Query query = parser.parse(QueryParser.escape(question));

        TopDocs topDocs = searcher.search(query, topK);

        List<Bm25SearchResult> results = new ArrayList<>();
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            org.apache.lucene.document.Document doc =
                    searcher.storedFields().document(scoreDoc.doc);

            int chunkIndex;
            try {
                chunkIndex = Integer.parseInt(doc.get("chunkIndex"));
            } catch (Exception e) {
                chunkIndex = -1;
            }

            // BM25 分不能直接与 Vector 相似度相加，融合使用 RRF
            results.add(new Bm25SearchResult(
                    doc.get("id"),
                    doc.get("source"),
                    chunkIndex,
                    scoreDoc.score,
                    doc.get("content")
            ));
        }

        return results;
    }

    /** 从 PostgreSQL 读取出来的一个 Chunk（仅 Bm25Retriever 内部使用）。 */
    private record PolicyChunk(
            String id,
            String source,
            String chunkIndex,
            String content
    ) {
    }
}
