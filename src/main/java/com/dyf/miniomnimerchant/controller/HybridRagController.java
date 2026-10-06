package com.dyf.miniomnimerchant.controller;

import com.dyf.miniomnimerchant.dto.HybridSearchResult;
import com.dyf.miniomnimerchant.service.HybridRagService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rag/hybrid")
public class HybridRagController {

    private final HybridRagService hybridRagService;


    public HybridRagController(
            HybridRagService hybridRagService
    ) {

        this.hybridRagService =
                hybridRagService;
    }


    /**
     * Hybrid RAG 测试
     *
     * 示例：
     *
     * /api/rag/hybrid/search?question=银行卡退款多久能到账
     */
    @GetMapping("/search")
    public List<HybridSearchResult> search(
            @RequestParam String question,

            @RequestParam(
                    defaultValue = "10"
            )
            int candidateK,

            @RequestParam(
                    defaultValue = "5"
            )
            int topK
    ) throws Exception {

        return hybridRagService.search(
                question,
                candidateK,
                topK
        );
    }


    /**
     * 测试 Controller
     */
    @GetMapping("/health")
    public String health() {

        return "Hybrid RAG is running";
    }
}