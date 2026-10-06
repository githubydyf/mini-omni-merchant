package com.omnimerchant.knowledge.tool;

import com.omnimerchant.knowledge.dto.PolicyAnswer;
import com.omnimerchant.knowledge.dto.RerankResult;
import com.omnimerchant.knowledge.service.RerankedRagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 政策知识库 Tool（Spring AI {@code @Tool}）。
 *
 * <p>本类是 Tool 适配层，职责单一：
 * <pre>
 * refundPolicyRAG(question)
 *   ↓
 * RerankedRagService（最终 RAG 检索入口）
 *   ↓
 * PolicyAnswer(context + citations)
 * </pre>
 *
 * <p>不实现业务检索算法，不自行查询 PgVector / Lucene；也<b>不生成最终客服回答</b>。
 * 最终回答由 Agent 主链（ReActAgentService + LLM）基于检索证据生成。
 *
 * <p>方法名 {@code refundPolicyRAG} 必须与 {@code AgentOrchestratorService} 中
 * POLICY_QA 的 toolAllowlist 完全一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyTools {

    private final RerankedRagService rerankedRagService;

    @Tool(description = """
            检索商家的退货、退款、换货、配送和售后政策知识库。
            当用户询问政策规则、退款条件、退货期限、退款到账时间、
            退货运费、质量问题售后规则等问题时调用。
            必须根据返回的真实政策证据回答，不得编造商家政策。
            """)
    public PolicyAnswer refundPolicyRAG(
            @ToolParam(description = "客户提出的完整政策问题。")
            String question) {

        try {
            log.info("调用 refundPolicyRAG，question='{}'", question);

            List<RerankResult> results = rerankedRagService.search(question, 10, 5);

            if (results == null || results.isEmpty()) {
                log.info("refundPolicyRAG 未检索到相关政策信息");
                return PolicyAnswer.error("当前政策知识库中没有检索到足够的信息。");
            }

            return PolicyAnswer.of(buildContext(results), buildCitations(results));

        } catch (Exception e) {
            log.error("refundPolicyRAG 检索失败：{}", e.getMessage());
            return PolicyAnswer.error("当前政策知识库中没有检索到足够的信息。");
        }
    }

    /** 构造给模型阅读的证据上下文（带来源与片段编号）。 */
    private String buildContext(List<RerankResult> results) {

        StringBuilder context = new StringBuilder();
        int index = 1;

        for (RerankResult result : results) {
            context.append("【来源 ").append(index).append("】\n")
                    .append("文件：").append(result.source()).append("\n")
                    .append("片段：").append(result.chunkIndex()).append("\n")
                    .append("内容：").append(result.content()).append("\n\n");
            index++;
        }

        return context.toString().trim();
    }

    /** 构造可追溯引用，只填写检索链当前真实拥有的字段。 */
    private List<PolicyAnswer.Citation> buildCitations(List<RerankResult> results) {

        return results.stream()
                .map(result -> new PolicyAnswer.Citation(
                        result.id(),
                        result.source(),
                        result.chunkIndex() == null ? -1 : result.chunkIndex(),
                        snippet(result.content()),
                        result.rerankScore()))
                .toList();
    }

    private String snippet(String content) {
        if (content == null) {
            return "";
        }
        String cleaned = content.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= 200 ? cleaned : cleaned.substring(0, 200).trim() + "...";
    }
}
