package com.omnimerchant.knowledge.service;

import com.omnimerchant.knowledge.dto.RerankResult;
import com.omnimerchant.knowledge.service.rerank.RerankOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证据评估单元测试：阈值边界与降级语义。
 *
 * <p>阈值取自真实实测分布：有证据问题最高分 0.65~0.99，无证据问题 0.15~0.32。
 */
class PolicyEvidenceEvaluatorTest {

    private final PolicyEvidenceEvaluator evaluator = new PolicyEvidenceEvaluator(70, 45);

    private RerankResult result(double rerankScore) {
        return new RerankResult("id", "refund-policy.txt", 1, rerankScore, 1, 1, "内容");
    }

    @Test
    void emptyResultsShouldBeNone() {
        var assessment = evaluator.assess(new RerankOutcome(List.of(), RerankOutcome.MODE_FALLBACK_EMPTY));
        assertThat(assessment.level()).isEqualTo("NONE");
        assertThat(assessment.usable()).isFalse();
        assertThat(assessment.refusalReason()).isNotBlank();
    }

    @Test
    void twoStrongResultsShouldBeSufficient() {
        var assessment = evaluator.assess(new RerankOutcome(
                List.of(result(0.88), result(0.76)), RerankOutcome.MODE_RERANKED));
        assertThat(assessment.level()).isEqualTo("SUFFICIENT");
        assertThat(assessment.usable()).isTrue();
        assertThat(assessment.refusalReason()).isNull();
    }

    @Test
    void singleStrongResultShouldNotBeSufficient() {
        // 只有 1 条时即使分数很高也不判 SUFFICIENT（与参考项目一致，需 ≥2 条）
        var assessment = evaluator.assess(new RerankOutcome(
                List.of(result(0.95)), RerankOutcome.MODE_RERANKED));
        assertThat(assessment.level()).isEqualTo("PARTIAL");
        assertThat(assessment.usable()).isTrue();
    }

    @Test
    void mediumAverageShouldBePartial() {
        var assessment = evaluator.assess(new RerankOutcome(
                List.of(result(0.55), result(0.50)), RerankOutcome.MODE_RERANKED));
        assertThat(assessment.level()).isEqualTo("PARTIAL");
        assertThat(assessment.usable()).isTrue();
        assertThat(assessment.refusalReason()).contains("不确定性");
    }

    @Test
    void lowAverageShouldBeWeakAndUnusable() {
        var assessment = evaluator.assess(new RerankOutcome(
                List.of(result(0.32), result(0.25), result(0.19)), RerankOutcome.MODE_RERANKED));
        assertThat(assessment.level()).isEqualTo("WEAK");
        assertThat(assessment.usable()).isFalse();
    }

    @Test
    void rerankerFallbackShouldNotFabricateLevelAndRemainUsable() {
        var assessment = evaluator.assess(new RerankOutcome(
                List.of(result(0.0), result(0.0)), RerankOutcome.MODE_FALLBACK_ERROR));
        assertThat(assessment.level()).isNull();
        assertThat(assessment.usable()).isTrue();
    }
}
