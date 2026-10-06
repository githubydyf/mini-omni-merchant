package com.dyf.miniomnimerchant.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PolicyRagService {

    private final VectorStore vectorStore;

    public PolicyRagService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<Document> search(String question) {

        return search(question, 5);
    }

    public List<Document> search(
            String question,
            int topK
    ) {

        SearchRequest request =
                SearchRequest.builder()
                        .query(question)
                        .topK(topK)
                        .similarityThresholdAll()
                        .build();

        return vectorStore.similaritySearch(request);
    }
}