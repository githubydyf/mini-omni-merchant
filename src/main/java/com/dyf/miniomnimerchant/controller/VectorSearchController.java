package com.dyf.miniomnimerchant.controller;

import com.dyf.miniomnimerchant.service.PolicyRagService;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag/vector")
public class VectorSearchController {

    private final PolicyRagService policyRagService;

    public VectorSearchController(
            PolicyRagService policyRagService) {

        this.policyRagService = policyRagService;
    }


    /**
     * 测试 Controller 是否正常
     *
     * GET:
     * http://localhost:8080/api/rag/vector/health
     */
    @GetMapping("/health")
    public String health() {

        return "Vector retriever is running";
    }


    /**
     * PgVector 向量检索测试
     *
     * GET:
     * http://localhost:8080/api/rag/vector/search?question=银行卡退款多久能到账
     */
    @GetMapping("/search")
    public List<Map<String, Object>> search(
            @RequestParam String question,
            @RequestParam(defaultValue = "10") int topK) {

        /*
         * 调用 PgVector similaritySearch
         */
        List<Document> documents =
                policyRagService.search(question,topK);


        /*
         * 为了方便 Postman 查看，
         * 把 Document 转成清晰的 JSON
         */
        List<Map<String, Object>> results =
                new ArrayList<>();


        for (Document document : documents) {

            Map<String, Object> result =
                    new LinkedHashMap<>();


            /*
             * Chunk 唯一 ID
             *
             * 后面做 RRF 时也会用到
             */
            result.put(
                    "id",
                    document.getId()
            );


            /*
             * metadata
             */
            Map<String, Object> metadata =
                    document.getMetadata();


            result.put(
                    "source",
                    metadata.getOrDefault(
                            "source",
                            "unknown"
                    )
            );


            /*
             * 你的数据库 metadata 使用的是：
             *
             * chunk_index
             */
            result.put(
                    "chunkIndex",
                    metadata.getOrDefault(
                            "chunk_index",
                            -1
                    )
            );


            /*
             * PgVector 相似度得分
             */
            result.put(
                    "score",
                    document.getScore()
            );


            /*
             * Chunk 正文
             */
            result.put(
                    "content",
                    document.getText()
            );


            results.add(result);
        }


        return results;
    }
}