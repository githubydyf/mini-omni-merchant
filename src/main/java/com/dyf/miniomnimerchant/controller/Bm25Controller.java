package com.dyf.miniomnimerchant.controller;

import com.dyf.miniomnimerchant.dto.Bm25SearchResult;
import com.dyf.miniomnimerchant.service.Bm25Retriever;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rag/bm25")
public class Bm25Controller {

    private final Bm25Retriever bm25Retriever;

    /**
     * 构造器注入 Bm25Retriever
     */
    public Bm25Controller(Bm25Retriever bm25Retriever) {
        this.bm25Retriever = bm25Retriever;
    }


    /**
     * BM25 检索测试接口
     *
     * 示例：
     *
     * GET /api/rag/bm25/search?question=七天无理由
     *
     * GET /api/rag/bm25/search?question=手机激活&topK
     */
    @GetMapping("/search")
    public List<Bm25SearchResult> search(
            @RequestParam String question,
            @RequestParam(defaultValue = "10") int topK
    ) throws Exception {

        return bm25Retriever.search(
                question,
                topK
        );
    }


    /**
     * 手动重新构建 BM25 索引
     *
     * 当 policy_vectors 中新增或更新知识后，
     * 可以调用这个接口重新加载 BM25 索引。
     *
     * POST /api/rag/bm25/rebuild
     */
    @PostMapping("/rebuild")
    public String rebuild() throws Exception {

        bm25Retriever.rebuildIndex();

        return "BM25 index rebuild success";
    }


    /**
     * 简单测试 Controller 是否正常
     *
     * GET /api/rag/bm25/health
     */
    @GetMapping("/health")
    public String health() {

        return "BM25 retriever is running";
    }
}