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
 *   <li>{@code evidenceLevel}：由真实 Reranker 分数计算出的证据等级
 *       （SUFFICIENT / PARTIAL / WEAK / NONE）。Reranker 降级时无法判定，返回 null；
 *       绝不伪造等级。</li>
 *   <li>{@code refusalReason}：证据不足或依据有限时的中文说明；无则为 null。</li>
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

    /**
     * 系统判定证据不足（WEAK / NONE）：不返回任何政策片段，强制模型不得据此作答。
     *
     * @param evidenceLevel 证据等级（当前为 NONE 或 WEAK）
     * @param reason        中文拒答原因
     */
    public static PolicyAnswer noEvidence(String evidenceLevel, String reason) {
        return new PolicyAnswer(null, null, reason, evidenceLevel, reason);
    }

    /** 检索成功：携带真实证据上下文与引用。 */
    public static PolicyAnswer of(String context, List<Citation> citations) {
        return new PolicyAnswer(context, citations, null, null, null);
    }

    /**
     * 检索成功且带有证据等级。
     *
     * @param evidenceLevel 证据等级：SUFFICIENT / PARTIAL；Reranker 降级无法判定时为 null
     * @param refusalReason PARTIAL 时提示模型需说明不确定性；否则为 null
     */
    public static PolicyAnswer of(String context, List<Citation> citations,
                                  String evidenceLevel, String refusalReason) {
        return new PolicyAnswer(context, citations, null, evidenceLevel, refusalReason);
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
