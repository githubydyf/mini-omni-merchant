package com.omnimerchant.knowledge.service;

import com.omnimerchant.knowledge.dto.RerankResult;
import com.omnimerchant.knowledge.service.rerank.RerankOutcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 政策证据评估：把检索结果换算成可判定的证据等级，并给出是否可用。
 *
 * <p>语义对齐参考项目 {@code RagContextPacker}：等级取
 * {@code SUFFICIENT / PARTIAL / WEAK / NONE}，由真实的 Reranker 相关性分数换算：
 *
 * <pre>
 * supportScore = rerankScore × 100
 * 全部结果为空                                  → NONE
 * 结果 ≥ 2 条且平均分 ≥ 70                       → SUFFICIENT（证据充分）
 * 平均分 ≥ 45                                   → PARTIAL（依据有限，需说明不确定性）
 * 平均分 &lt; 45                                   → WEAK（证据不足，应拒答）
 * </pre>
 *
 * <p>关键约束：<b>Reranker 降级时不能用 0 分判断证据强度</b>。降级返回的
 * {@code rerankScore=0} 只表示“未经重排”，不是“相关性为 0”。因此降级时
 * 等级返回 {@code null}（能力不可用，不伪造等级），但仍返回检索到的真实片段，
 * 由模型在缺少重排校验的情况下谨慎作答，避免一次 Reranker 故障导致全部政策查询失败。
 */
@Service
public class PolicyEvidenceEvaluator {

    /** 证据充分：≥2 条且平均 supportScore 达到该值。 */
    private final double sufficientSupportScore;
    /** 最低可接受平均 supportScore，低于该值判定为证据不足。 */
    private final double minSupportScore;

    public PolicyEvidenceEvaluator(
            @Value("${app.retrieval.sufficient-support-score:70}") double sufficientSupportScore,
            @Value("${app.retrieval.min-support-score:45}") double minSupportScore) {
        this.sufficientSupportScore = sufficientSupportScore;
        this.minSupportScore = minSupportScore;
    }

    /**
     * 评估一次检索的结果。
     *
     * @param outcome Reranker 执行结果（含是否降级）
     * @return 证据评估结论
     */
    public EvidenceAssessment assess(RerankOutcome outcome) {

        List<RerankResult> results = outcome.results();

        if (results == null || results.isEmpty()) {
            return new EvidenceAssessment(
                    "NONE", false, "当前政策知识库中没有检索到足够的信息。");
        }

        // Reranker 不可用（降级）：没有真实相关性分数，不伪造等级，也不拒答。
        if (!outcome.reranked()) {
            return new EvidenceAssessment(null, true, null);
        }

        double average = results.stream()
                .mapToDouble(result -> toSupportScore(result.rerankScore()))
                .average()
                .orElse(0.0);

        if (results.size() >= 2 && average >= sufficientSupportScore) {
            return new EvidenceAssessment("SUFFICIENT", true, null);
        }
        if (average >= minSupportScore) {
            return new EvidenceAssessment("PARTIAL", true,
                    "政策依据有限，回答时必须明确说明不确定性。");
        }
        return new EvidenceAssessment(
                "WEAK", false, "当前政策知识库中没有检索到足够的信息。");
    }

    /** rerankScore(0~1) → supportScore(0~100)。 */
    private double toSupportScore(double rerankScore) {
        return Math.round(Math.min(100.0, Math.max(0.0, rerankScore) * 100.0) * 10.0) / 10.0;
    }

    /**
     * 证据评估结论。
     *
     * @param level         SUFFICIENT / PARTIAL / WEAK / NONE；Reranker 降级时为 null
     * @param usable        是否可以向模型提供证据（false 表示应拒答）
     * @param refusalReason 拒答或需说明不确定性的中文原因；无则为 null
     */
    public record EvidenceAssessment(String level, boolean usable, String refusalReason) {
    }
}
