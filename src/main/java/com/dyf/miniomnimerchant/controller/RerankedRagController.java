package com.dyf.miniomnimerchant.controller;

import com.dyf.miniomnimerchant.dto.RerankResult;
import com.dyf.miniomnimerchant.service.RerankedRagService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rag/rerank")
public class RerankedRagController {

    private final RerankedRagService
            rerankedRagService;


    public RerankedRagController(
            RerankedRagService
                    rerankedRagService
    ) {

        this.rerankedRagService =
                rerankedRagService;
    }


    @GetMapping("/health")
    public String health() {

        return "Reranker is running";
    }


    /**
     * 示例：
     *
     * /api/rag/rerank/search
     * ?question=因为地址写错导致包裹退回重新寄运费谁承担
     * &candidateK=10
     * &topK=5
     */
    @GetMapping("/search")
    public List<RerankResult> search(

            @RequestParam
            String question,

            @RequestParam(
                    defaultValue = "10"
            )
            int candidateK,

            @RequestParam(
                    defaultValue = "5"
            )
            int topK

    ) throws Exception {

        return rerankedRagService
                .search(
                        question,
                        candidateK,
                        topK
                );
    }
}