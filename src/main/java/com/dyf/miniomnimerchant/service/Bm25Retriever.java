package com.dyf.miniomnimerchant.service;

import com.dyf.miniomnimerchant.dto.Bm25SearchResult;
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

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class Bm25Retriever {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 中文分词器
     */
    private final Analyzer analyzer =
            new SmartChineseAnalyzer();

    /**
     * Lucene 内存索引
     */
    private Directory directory;

    /**
     * Lucene IndexReader
     */
    private DirectoryReader reader;

    /**
     * Lucene 查询器
     */
    private IndexSearcher searcher;




    public Bm25Retriever(
            @Qualifier("pgVectorJdbcTemplate")
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate = jdbcTemplate;
    }




    /**
     * Spring Boot 启动后自动建立 BM25 索引
     */
    @PostConstruct
    public void init() {

        try {

            rebuildIndex();

        } catch (Exception e) {

            throw new IllegalStateException(
                    "BM25 索引初始化失败",
                    e
            );
        }
    }


    /**
     * 从 PostgreSQL policy_vectors
     * 重新建立 BM25 索引
     */
    public synchronized void rebuildIndex() throws Exception {

        System.out.println(
                "========== 开始构建 BM25 索引 =========="
        );


        /*
         * ------------------------------------------------
         * 1. 从 PostgreSQL 读取所有 Chunk
         * ------------------------------------------------
         */

        String sql = """
                SELECT
                    id,
                    content,
                    metadata ->> 'source' AS source,
                    metadata ->> 'chunk_index' AS chunkIndex
                FROM policy_vectors
                """;


        List<PolicyChunk> chunks =
                jdbcTemplate.query(
                        sql,
                        (rs, rowNum) -> {

                            String id =
                                    rs.getString("id");

                            String content =
                                    rs.getString("content");

                            String source =
                                    rs.getString("source");

                            String chunkIndex =
                                    rs.getString("chunkIndex");


                            /*
                             * metadata 中没有对应字段时
                             * 给一个默认值
                             */
                            if (source == null) {
                                source = "unknown";
                            }

                            if (chunkIndex == null) {
                                chunkIndex = "-1";
                            }


                            return new PolicyChunk(
                                    id,
                                    source,
                                    chunkIndex,
                                    content
                            );
                        }
                );


        System.out.println(
                "从 policy_vectors 读取 Chunk 数量："
                        + chunks.size()
        );


        /*
         * ------------------------------------------------
         * 2. 创建新的 Lucene 内存索引
         * ------------------------------------------------
         */

        Directory newDirectory =
                new ByteBuffersDirectory();


        IndexWriterConfig config =
                new IndexWriterConfig(analyzer);


        /*
         * 使用 BM25
         *
         * 默认：
         *
         * k1 = 1.2
         * b  = 0.75
         */
        config.setSimilarity(
                new BM25Similarity()
        );


        /*
         * ------------------------------------------------
         * 3. 把 PostgreSQL Chunk 写入 Lucene
         * ------------------------------------------------
         */

        try (IndexWriter writer =
                     new IndexWriter(
                             newDirectory,
                             config
                     )) {


            for (PolicyChunk chunk : chunks) {


                org.apache.lucene.document.Document luceneDocument =
                        new org.apache.lucene.document.Document();


                /*
                 * Chunk 唯一 ID
                 *
                 * 后面做 RRF 时非常重要
                 */
                luceneDocument.add(
                        new StringField(
                                "id",
                                chunk.id(),
                                Field.Store.YES
                        )
                );


                /*
                 * 文档来源
                 */
                luceneDocument.add(
                        new StringField(
                                "source",
                                chunk.source(),
                                Field.Store.YES
                        )
                );


                /*
                 * Chunk 编号
                 */
                luceneDocument.add(
                        new StringField(
                                "chunkIndex",
                                chunk.chunkIndex(),
                                Field.Store.YES
                        )
                );


                /*
                 * Chunk 正文
                 *
                 * TextField 会经过
                 * SmartChineseAnalyzer 分词
                 *
                 * BM25 真正检索的就是这个字段
                 */
                luceneDocument.add(
                        new TextField(
                                "content",
                                chunk.content(),
                                Field.Store.YES
                        )
                );


                writer.addDocument(
                        luceneDocument
                );
            }
        }


        /*
         * ------------------------------------------------
         * 4. 创建 IndexSearcher
         * ------------------------------------------------
         */

        DirectoryReader newReader =
                DirectoryReader.open(
                        newDirectory
                );


        IndexSearcher newSearcher =
                new IndexSearcher(
                        newReader
                );


        /*
         * 查询阶段同样使用 BM25
         */
        newSearcher.setSimilarity(
                new BM25Similarity()
        );


        /*
         * ------------------------------------------------
         * 5. 关闭旧索引
         * ------------------------------------------------
         */

        if (this.reader != null) {

            this.reader.close();
        }


        if (this.directory != null) {

            this.directory.close();
        }


        /*
         * ------------------------------------------------
         * 6. 替换为新索引
         * ------------------------------------------------
         */

        this.directory =
                newDirectory;

        this.reader =
                newReader;

        this.searcher =
                newSearcher;


        System.out.println(
                "========== BM25 索引构建完成，文档数："
                        + newReader.numDocs()
                        + " =========="
        );
    }


    /**
     * BM25 查询
     */
    public List<Bm25SearchResult> search(
            String question,
            int topK
    ) throws Exception {


        /*
         * 基础参数检查
         */
        if (question == null ||
                question.isBlank()) {

            return List.of();
        }


        if (topK <= 0) {

            return List.of();
        }


        /*
         * 确保索引已经初始化
         */
        if (searcher == null) {

            throw new IllegalStateException(
                    "BM25 索引尚未初始化"
            );
        }


        /*
         * ------------------------------------------------
         * 1. 创建 QueryParser
         * ------------------------------------------------
         *
         * content 字段使用：
         *
         * SmartChineseAnalyzer
         *
         * 进行中文分词
         */

        QueryParser parser =
                new QueryParser(
                        "content",
                        analyzer
                );


        /*
         * 防止用户输入：
         *
         * +
         * -
         * :
         * (
         * )
         *
         * 等 Lucene 特殊语法导致解析异常
         */
        String escapedQuestion =
                QueryParser.escape(
                        question
                );


        /*
         * ------------------------------------------------
         * 2. 解析查询
         * ------------------------------------------------
         */

        Query query =
                parser.parse(
                        escapedQuestion
                );


        /*
         * ------------------------------------------------
         * 3. 执行 BM25 检索
         * ------------------------------------------------
         */

        TopDocs topDocs =
                searcher.search(
                        query,
                        topK
                );


        /*
         * ------------------------------------------------
         * 4. 转换成自己的 DTO
         * ------------------------------------------------
         */

        List<Bm25SearchResult> results =
                new ArrayList<>();


        for (ScoreDoc scoreDoc :
                topDocs.scoreDocs) {


            org.apache.lucene.document.Document doc =
                    searcher
                            .storedFields()
                            .document(
                                    scoreDoc.doc
                            );


            /*
             * 读取 chunkIndex
             */
            int chunkIndex;

            try {

                chunkIndex =
                        Integer.parseInt(
                                doc.get(
                                        "chunkIndex"
                                )
                        );

            } catch (Exception e) {

                chunkIndex = -1;
            }


            /*
             * BM25 score
             *
             * 注意：
             *
             * 这个 score 不能直接和
             * Vector similarity score 相加
             *
             * 后面使用 RRF 做排名融合
             */
            results.add(
                    new Bm25SearchResult(
                            doc.get("id"),
                            doc.get("source"),
                            chunkIndex,
                            scoreDoc.score,
                            doc.get("content")
                    )
            );
        }


        return results;
    }


    /**
     * PostgreSQL 中读取出来的
     * 一个知识库 Chunk
     * <p>
     * 只在 Bm25Retriever 内部使用
     */
    private record PolicyChunk(
            String id,
            String source,
            String chunkIndex,
            String content
    ) {
    }
}