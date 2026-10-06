package com.omnimerchant.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 政策 RAG 检索的结构化结果，作为 {@code PolicyTools.refundPolicyRAG} 的返回值。
 *
 * <p>职责边界：本类只承载“检索到的政策证据”，<b>不生成最终客服回答</b>。
 * 最终中文回答由 Agent 主链（ReActAgentService + LLM）基于 {@code context} 生成。
 *
 * <ul>
 *   <li>{@code context}：给模型阅读的证据上下文（带来源与片段编号）。</li>
 *   <li>{@code citations}：给系统保留的可追溯信息（真实字段）。</li>
 *   <li>{@code error}：检索失败或无可靠结果时的中文提示；成功时为 null。</li>
 *   <li>{@code evidenceLevel} / {@code refusalReason}：为后续证据分级预留。
 *       当前没有真实的证据等级计算能力，因此 {@code evidenceLevel} 一律为 null，
 *       不伪造 HIGH / MEDIUM / LOW。</li>
 * </ul>
 */
public record PolicyAnswer(
        @JsonInclude(JsonInclude.Include.NON_NULL) String context,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Citation> citations,
        @JsonInclude(JsonInclude.Include.NON_NULL) String error,
        @JsonInclude(JsonInclude.Include.NON_NULL) String evidenceLevel,
        @JsonInclude(JsonInclude.Include.NON_NULL) String refusalReason) {

    /** 便捷构造：不涉及证据等级的场景。 */
    public PolicyAnswer(String context, List<Citation> citations, String error) {
        this(context, citations, error, null, null);
    }

    /** 检索失败 / 无可靠证据：只返回错误语义，不携带任何伪造的政策内容。 */
    public static PolicyAnswer error(String message) {
        return new PolicyAnswer(null, null, message, null, message);
    }

    /** 检索成功：携带真实证据上下文与引用。 */
    public static PolicyAnswer of(String context, List<Citation> citations) {
        return new PolicyAnswer(context, citations, null, null, null);
    }

    /**
     * 政策引用信息，只填写检索链当前<b>真实拥有</b>的字段。
     *
     * <p>参考项目中的 docUuid / sectionPath / supportScore / chunkVersion 等字段，
     * 当前工程无法真实获取，因此不在此暴露，避免伪造可追溯信息。
     *
     * @param chunkId     Chunk 唯一 ID
     * @param source      政策文件名
     * @param chunkIndex  文件内分块序号（无法解析时为 -1）
     * @param snippet     片段摘要（正文截断，用于追溯展示）
     * @param rerankScore Reranker 相关性分数（Reranker 降级时为 0）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Citation(
            String chunkId,
            String source,
            int chunkIndex,
            String snippet,
            double rerankScore) {
    }
}
