package com.dyf.miniomnimerchant.controller;

import com.dyf.miniomnimerchant.service.PolicyRagService;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagTestController {

    private final PolicyRagService policyRagService;


    public RagTestController(
            PolicyRagService policyRagService) {

        this.policyRagService =
                policyRagService;
    }


    @GetMapping("/search")
    public List<Map<String, Object>> search(
            @RequestParam String q) {

        return policyRagService
                .search(q)
                .stream()
                .map(this::toResult)
                .toList();
    }


    private Map<String, Object> toResult(
            Document document) {

        Map<String, Object> result =
                new LinkedHashMap<>();

        result.put(
                "source",
                document.getMetadata()
                        .get("source")
        );

        result.put(
                "chunkIndex",
                document.getMetadata()
                        .get("chunk_index")
        );

        result.put(
                "score",
                document.getScore()
        );

        result.put(
                "text",
                document.getText()
        );

        return result;
    }
}