package com.dyf.miniomnimerchant.service;

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

@Component
public class PolicyKnowledgeLoader {

    private final VectorStore vectorStore;
    private final ResourcePatternResolver resourcePatternResolver;

    public PolicyKnowledgeLoader(
            VectorStore vectorStore,
            ResourcePatternResolver resourcePatternResolver) {

        this.vectorStore = vectorStore;
        this.resourcePatternResolver = resourcePatternResolver;
    }


    /**
     * 重新加载全部政策文件
     */
    public Map<String, Integer> reloadAll() {

        try {

            Resource[] resources =
                    resourcePatternResolver.getResources(
                            "classpath*:konwledges/*.txt"
                    );

            System.out.println(
                    "找到政策文件数量：" + resources.length
            );

            Map<String, Integer> result =
                    new LinkedHashMap<>();

            for (Resource resource : resources) {

                int chunkCount =
                        reloadResource(resource);

                result.put(
                        resource.getFilename(),
                        chunkCount
                );
            }

            return result;

        } catch (Exception e) {

            throw new RuntimeException(
                    "重新加载全部知识库失败",
                    e
            );
        }
    }


    /**
     * 重新加载指定的一个文件
     * <p>
     * 例如：
     * return-policy.txt
     */
    public int reloadOne(String fileName) {

        try {

            Resource resource =
                    resourcePatternResolver.getResource(
                            "classpath:konwledges/" + fileName
                    );

            if (!resource.exists()) {

                throw new IllegalArgumentException(
                        "知识文件不存在：" + fileName
                );
            }

            return reloadResource(resource);

        } catch (Exception e) {

            throw new RuntimeException(
                    "重新加载知识文件失败：" + fileName,
                    e
            );
        }
    }


    /**
     * 一个文件的具体更新过程
     */
    private int reloadResource(
            Resource resource
    ) throws Exception {

        String source =
                resource.getFilename();

        System.out.println();
        System.out.println(
                "=================================="
        );

        System.out.println(
                "开始更新政策文件：" + source
        );


        // ==============================
        // 1. 删除该文件以前的旧 Chunk
        // ==============================

        vectorStore.delete(
                "source == '" + source + "'"
        );

        System.out.println(
                "已删除旧向量数据：" + source
        );


        // ==============================
        // 2. 读取最新文件
        // ==============================

        String text =
                resource.getContentAsString(
                        StandardCharsets.UTF_8
                );

        System.out.println(
                "读取文档：" +
                        source +
                        "，字符数：" +
                        text.length()
        );


        Document originalDocument =
                Document.builder()
                        .text(text)
                        .metadata(
                                "source",
                                source
                        )
                        .build();


        // ==============================
        // 3. 配置 TokenTextSplitter
        // ==============================

        TokenTextSplitter splitter =
                TokenTextSplitter.builder()

                        .withChunkSize(500)

                        .withMinChunkSizeChars(200)

                        .withMinChunkLengthToEmbed(20)

                        .withMaxNumChunks(100)

                        .withKeepSeparator(true)

                        .withPunctuationMarks(
                                List.of(
                                        '。',
                                        '？',
                                        '！',
                                        '；',
                                        '\n'
                                )
                        )

                        .build();


        // ==============================
        // 4. 执行切块
        // ==============================

        List<Document> chunks =
                splitter.apply(
                        List.of(originalDocument)
                );


        // ==============================
        // 5. 添加 chunk_index
        // ==============================

        List<Document> chunkDocuments =
                new ArrayList<>();


        for (int i = 0; i < chunks.size(); i++) {

            Document chunk =
                    chunks.get(i);


            Document newChunk =
                    Document.builder()

                            .text(
                                    chunk.getText()
                            )

                            .metadata(
                                    chunk.getMetadata()
                            )

                            .metadata(
                                    "source",
                                    source
                            )

                            .metadata(
                                    "chunk_index",
                                    i
                            )

                            .build();


            chunkDocuments.add(
                    newChunk
            );


            System.out.println();

            System.out.println(
                    "========== "
                            + source
                            + " Chunk "
                            + i
                            + " =========="
            );

            System.out.println(
                    "length = "
                            + newChunk
                            .getText()
                            .length()
            );
        }


        // ==============================
        // 6. 重新 Embedding + 写 PgVector
        // ==============================

        vectorStore.add(
                chunkDocuments
        );


        System.out.println();

        System.out.println(
                source
                        + " 更新完成，Chunk 数量："
                        + chunkDocuments.size()
        );

        System.out.println(
                "=================================="
        );


        return chunkDocuments.size();
    }
}