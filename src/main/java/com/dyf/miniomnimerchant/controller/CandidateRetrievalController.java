package com.dyf.miniomnimerchant.controller;

import com.dyf.miniomnimerchant.dto.RetrievalCandidate;
import com.dyf.miniomnimerchant.service.CandidateRetrievalService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rag/candidates")
public class CandidateRetrievalController {

    private final CandidateRetrievalService
            candidateRetrievalService;


    public CandidateRetrievalController(
            CandidateRetrievalService
                    candidateRetrievalService
    ) {

        this.candidateRetrievalService =
                candidateRetrievalService;
    }


    /**
     * 测试：
     *
     * /api/rag/candidates/search
     * ?question=因为地址写错导致包裹退回重新寄运费谁出
     * &topK=10
     */
    @GetMapping("/search")
    public List<RetrievalCandidate> search(
            @RequestParam String question,

            @RequestParam(
                    defaultValue = "10"
            )
            int topK
    ) throws Exception {

        return candidateRetrievalService
                .retrieve(
                        question,
                        topK
                );
    }
}