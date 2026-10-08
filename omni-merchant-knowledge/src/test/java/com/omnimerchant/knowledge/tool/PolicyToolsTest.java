package com.omnimerchant.knowledge.tool;

import com.omnimerchant.knowledge.dto.PolicyAnswer;
import com.omnimerchant.knowledge.dto.RerankResult;
import com.omnimerchant.knowledge.service.PolicyEvidenceEvaluator;
import com.omnimerchant.knowledge.service.RerankedRagService;
import com.omnimerchant.knowledge.service.rerank.RerankOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PolicyTools 适配层单元测试（不需要真实 PgVector / Reranker）。
 *
 * <p>验证：
 * <ul>
 *   <li>证据充分时 {@code context} / {@code citations} 非空，字段真实，等级为 SUFFICIENT；</li>
 *   <li>证据低于阈值时系统层拒答（不返回任何政策片段），等级 WEAK/NONE；</li>
 *   <li>Reranker 降级时不拒答、不伪造等级；</li>
 *   <li>检索异常返回中文 error，不伪造政策内容。</li>
 * </ul>
 */
class PolicyToolsTest {

    /** 使用默认阈值（min=45，sufficient=70）构建真实评估器。 */
    private PolicyEvidenceEvaluator evaluator() {
        return new PolicyEvidenceEvaluator(70, 45);
    }

    private PolicyTools policyTools(RerankedRagService rag) {
        return new PolicyTools(rag, evaluator());
    }

    @Test
    void shouldBuildContextAndCitationsWithSufficientEvidence() throws Exception {
        var rag = mock(RerankedRagService.class);
        when(rag.searchWithEvidence(anyString(), anyInt(), anyInt())).thenReturn(new RerankOutcome(List.of(
                new RerankResult("chunk-1", "refund-policy.txt", 3, 0.93, 1, 1, "退款应在 7 天内到账。"),
                new RerankResult("chunk-2", "return-policy.txt", 5, 0.88, 2, 2, "商品签收后 7 天内可申请退货。")),
                RerankOutcome.MODE_RERANKED));

        PolicyAnswer answer = policyTools(rag).refundPolicyRAG("退款多久到账？");

        assertThat(answer.error()).isNull();
        assertThat(answer.evidenceLevel()).isEqualTo("SUFFICIENT");
        assertThat(answer.context()).contains("refund-policy.txt").contains("退款应在 7 天内到账。");
        assertThat(answer.citations()).hasSize(2);
        assertThat(answer.citations().get(0).chunkId()).isEqualTo("chunk-1");
        assertThat(answer.citations().get(0).source()).isEqualTo("refund-policy.txt");
        assertThat(answer.citations().get(0).chunkIndex()).isEqualTo(3);
        assertThat(answer.citations().get(0).rerankScore()).isEqualTo(0.93);
    }

    @Test
    void weakEvidenceShouldBeSystemRefusedWithoutAnyPolicyContent() throws Exception {
        var rag = mock(RerankedRagService.class);
        // 无证据问题实测分数 ~0.25~0.32，低于 45 阈值
        when(rag.searchWithEvidence(anyString(), anyInt(), anyInt())).thenReturn(new RerankOutcome(List.of(
                new RerankResult("c1", "refund-policy.txt", 1, 0.32, 1, 1, "无关片段 A"),
                new RerankResult("c2", "return-policy.txt", 2, 0.25, 2, 2, "无关片段 B")),
                RerankOutcome.MODE_RERANKED));

        PolicyAnswer answer = policyTools(rag).refundPolicyRAG("特殊会员终身退款规则是什么？");

        assertThat(answer.evidenceLevel()).isEqualTo("WEAK");
        assertThat(answer.context()).isNull();
        assertThat(answer.citations()).isNull();
        assertThat(answer.error()).isEqualTo("当前政策知识库中没有检索到足够的信息。");
    }

    @Test
    void partialEvidenceShouldStillAnswerButFlagUncertainty() throws Exception {
        var rag = mock(RerankedRagService.class);
        // 平均分落在 [45,70) → PARTIAL
        when(rag.searchWithEvidence(anyString(), anyInt(), anyInt())).thenReturn(new RerankOutcome(List.of(
                new RerankResult("c1", "refund-policy.txt", 1, 0.55, 1, 1, "部分相关片段"),
                new RerankResult("c2", "return-policy.txt", 2, 0.50, 2, 2, "部分相关片段二")),
                RerankOutcome.MODE_RERANKED));

        PolicyAnswer answer = policyTools(rag).refundPolicyRAG("某个模糊的政策问题");

        assertThat(answer.evidenceLevel()).isEqualTo("PARTIAL");
        assertThat(answer.context()).isNotNull();
        assertThat(answer.refusalReason()).contains("不确定性");
    }

    @Test
    void rerankerFallbackShouldNotRefuseAndNotFabricateLevel() throws Exception {
        var rag = mock(RerankedRagService.class);
        // 降级：rerankScore 恒为 0，模式为 fallback-error
        when(rag.searchWithEvidence(anyString(), anyInt(), anyInt())).thenReturn(new RerankOutcome(List.of(
                new RerankResult("c1", "refund-policy.txt", 1, 0.0, 1, 1, "召回片段")),
                RerankOutcome.MODE_FALLBACK_ERROR));

        PolicyAnswer answer = policyTools(rag).refundPolicyRAG("退款多久到账？");

        assertThat(answer.error()).isNull();
        assertThat(answer.evidenceLevel()).isNull();
        assertThat(answer.context()).isNotNull();
        assertThat(answer.citations()).hasSize(1);
    }

    @Test
    void emptyResultsShouldReturnChineseErrorWithoutFabrication() throws Exception {
        var rag = mock(RerankedRagService.class);
        when(rag.searchWithEvidence(anyString(), anyInt(), anyInt()))
                .thenReturn(new RerankOutcome(List.of(), RerankOutcome.MODE_FALLBACK_EMPTY));

        PolicyAnswer answer = policyTools(rag).refundPolicyRAG("特殊会员终身退款规则？");

        assertThat(answer.evidenceLevel()).isEqualTo("NONE");
        assertThat(answer.context()).isNull();
        assertThat(answer.error()).isEqualTo("当前政策知识库中没有检索到足够的信息。");
    }

    @Test
    void retrievalFailureShouldReturnChineseErrorNotThrow() throws Exception {
        var rag = mock(RerankedRagService.class);
        when(rag.searchWithEvidence(anyString(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("PgVector 不可用"));

        PolicyAnswer answer = policyTools(rag).refundPolicyRAG("退货期限是多少天？");

        assertThat(answer.error()).isEqualTo("当前政策知识库中没有检索到足够的信息。");
        assertThat(answer.context()).isNull();
    }
}
