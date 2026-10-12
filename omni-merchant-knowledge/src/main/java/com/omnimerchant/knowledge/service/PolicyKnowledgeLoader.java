package com.omnimerchant.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 政策知识库加载器。
 *
 * <p>迁移自旧单体同名类。流程：
 *
 * <pre>
 * 读取 knowledge/*.txt
 *   ↓
 * 删除该文件的旧 Vector Chunk（按 metadata.source 过滤）
 *   ↓
 * TokenTextSplitter 切块
 *   ↓
 * 写入 metadata（source / chunk_index）
 *   ↓
 * Embedding + VectorStore.add()
 *   ↓
 * 全部更新完成后重建 BM25 索引
 * </pre>
 *
 * <p><b>注意</b>：资源目录已从旧单体的错误拼写 {@code konwledges} 统一为
 * {@code knowledge}。
 *
 * <p><b>Tenant 边界</b>：仓库内置的 policy/*.txt 全部标记为<b>共享演示语料</b>
 * （metadata {@code tenant_id = 0}，即 {@link RagTenantScope#GLOBAL_TENANT_ID}），
 * 对所有租户可见；<b>禁止</b>将租户私有政策写入该共享索引。需要租户私有政策时，
 * 应写入带真实 {@code tenant_id} 的独立文档，由检索路径按租户过滤。
 */
@Slf4j
@Component
public class PolicyKnowledgeLoader {

    private static final String KNOWLEDGE_LOCATION = "classpath*:knowledge/*.txt";

    private final VectorStore vectorStore;
    private final ResourcePatternResolver resourcePatternResolver;
    private final Bm25Retriever bm25Retriever;

    public PolicyKnowledgeLoader(
            VectorStore vectorStore,
            ResourcePatternResolver resourcePatternResolver,
            Bm25Retriever bm25Retriever) {
        this.vectorStore = vectorStore;
        this.resourcePatternResolver = resourcePatternResolver;
        this.bm25Retriever = bm25Retriever;
    }

    /**
     * 重新加载全部政策文件，并同步重建 BM25 索引。
     *
     * @return 文件名 → 该文件的 Chunk 数量
     */
    public Map<String, Integer> reloadAll() {

        try {
            Resource[] resources = resourcePatternResolver.getResources(KNOWLEDGE_LOCATION);
            log.info("找到政策文件数量：{}", resources.length);

            Map<String, Integer> result = new LinkedHashMap<>();
            for (Resource resource : resources) {
                result.put(resource.getFilename(), reloadResource(resource));
            }

            rebuildBm25Index();
            return result;

        } catch (Exception e) {
            throw new RuntimeException("重新加载全部知识库失败", e);
        }
    }

    /**
     * 重新加载指定的一个文件（例如 {@code return-policy.txt}），并同步重建 BM25 索引。
     */
    public int reloadOne(String fileName) {

        try {
            Resource resource = resourcePatternResolver.getResource("classpath:knowledge/" + fileName);
            if (!resource.exists()) {
                throw new IllegalArgumentException("知识文件不存在：" + fileName);
            }

            int chunkCount = reloadResource(resource);
            rebuildBm25Index();
            return chunkCount;

        } catch (Exception e) {
            throw new RuntimeException("重新加载知识文件失败：" + fileName, e);
        }
    }

    /** reloadAll 内部复用：不自触发 BM25，由外层统一重建一次。 */
    private int reloadResource(Resource resource) throws Exception {

        String source = resource.getFilename();
        log.info("开始更新政策文件（共享演示语料）：{}", source);

        // 1. 删除该文件以前的旧 Chunk（仅限全局演示语料，避免误删租户私有文档）
        vectorStore.delete("source == '" + source + "' && tenant_id == '"
                + RagTenantScope.GLOBAL_TENANT_ID + "'");
        log.info("已删除旧向量数据：{}", source);

        // 2. 读取最新文件
        String text = resource.getContentAsString(StandardCharsets.UTF_8);
        log.info("读取文档：{}，字符数：{}", source, text.length());

        Document originalDocument = Document.builder()
                .text(text)
                .metadata("source", source)
                .metadata("tenant_id", RagTenantScope.metadataTenantId())
                .build();

        // 3. TokenTextSplitter：面向中文政策文本，使用中文标点作为分隔
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(200)
                .withMinChunkLengthToEmbed(20)
                .withMaxNumChunks(100)
                .withKeepSeparator(true)
                .withPunctuationMarks(List.of('。', '？', '！', '；', '\n'))
                .build();

        List<Document> chunks = splitter.apply(List.of(originalDocument));

        // 4. 补充 chunk_index，并显式标注 tenant_id=0（全局共享语料）
        List<Document> chunkDocuments = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Document newChunk = Document.builder()
                    .text(chunk.getText())
                    .metadata(chunk.getMetadata())
                    .metadata("source", source)
                    .metadata("tenant_id", RagTenantScope.metadataTenantId())
                    .metadata("chunk_index", i)
                    .build();
            chunkDocuments.add(newChunk);
            log.info("{} Chunk {} length = {}", source, i, newChunk.getText().length());
        }

        // 5. Embedding + 写入 PgVector
        vectorStore.add(chunkDocuments);

        log.info("{} 更新完成，Chunk 数量：{}", source, chunkDocuments.size());
        return chunkDocuments.size();
    }

    /** 知识库更新后必须重建 BM25，避免 BM25 仍指向旧数据。 */
    private void rebuildBm25Index() {
        try {
            bm25Retriever.rebuildIndex();
        } catch (Exception e) {
            throw new RuntimeException("知识库已更新，但 BM25 索引重建失败", e);
        }
    }
}
