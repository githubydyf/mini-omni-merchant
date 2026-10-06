package com.dyf.miniomnimerchant.tool;

import com.dyf.miniomnimerchant.dto.RerankResult;
import com.dyf.miniomnimerchant.service.RerankedRagService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PolicyTools {

    private final RerankedRagService rerankedRagService;


    public PolicyTools(
            RerankedRagService rerankedRagService
    ) {
        this.rerankedRagService = rerankedRagService;
    }


    @Tool(
            description = """
                    搜索商家的政策知识库。
                    当用户询问退货、退款、配送、
                    售后规则等政策问题时调用。
                    参数 question 应为用户完整问题。
                    """
    )
    public String searchPolicy(String question) {

        try {

            /*
             * Retrieval Pipeline:
             *
             * Vector Top10
             * +
             * BM25 Top10
             * ↓
             * 按 Chunk ID 去重
             * ↓
             * Candidate Pool
             * ↓
             * qwen3.7-text-rerank
             * ↓
             * Final Top5
             */
            List<RerankResult> results =
                    rerankedRagService.search(
                            question,
                            10,
                            5
                    );


            if (results == null || results.isEmpty()) {
                return "未找到相关政策信息。";
            }


            StringBuilder context =
                    new StringBuilder();


            for (RerankResult result : results) {

                context.append("来源：")
                        .append(result.source())
                        .append("\n");

                context.append("Chunk：")
                        .append(result.chunkIndex())
                        .append("\n");

                context.append("内容：")
                        .append(result.content())
                        .append("\n\n");
            }


            return context.toString();


        } catch (Exception e) {

            throw new RuntimeException(
                    "政策知识库检索失败",
                    e
            );
        }
    }
}