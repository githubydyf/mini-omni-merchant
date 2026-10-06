package com.omnimerchant.knowledge.tool;

import com.omnimerchant.knowledge.dto.PolicyAnswer;
import com.omnimerchant.knowledge.dto.RerankResult;
import com.omnimerchant.knowledge.service.RerankedRagService;
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
 * <p>验证 §39：
 * <ul>
 *   <li>{@code context} 非空，且来自真实检索结果</li>
 *   <li>{@code citations} 非空，source / chunkIndex / rerankScore 为真实字段</li>
 *   <li>空结果与异常都返回中文 error，不伪造政策内容</li>
 * </ul>
 */
class PolicyToolsTest {

    @Test
    void shouldBuildContextAndCitationsFromRetrieval() throws Exception {
        var rag = mock(RerankedRagService.class);
        when(rag.search(anyString(), anyInt(), anyInt())).thenReturn(List.of(
                new RerankResult("chunk-1", "refund-policy.txt", 3, 0.93, 1, 1, "退款应在 7 天内到账。"),
                new RerankResult("chunk-2", "return-policy.txt", 5, 0.81, 2, 2, "商品签收后 7 天内可申请退货。")));

        PolicyAnswer answer = new PolicyTools(rag).refundPolicyRAG("退款多久到账？");

        assertThat(answer.error()).isNull();
        assertThat(answer.context()).isNotNull();
        assertThat(answer.context()).contains("refund-policy.txt");
        assertThat(answer.context()).contains("退款应在 7 天内到账。");
        assertThat(answer.citations()).hasSize(2);
        assertThat(answer.citations().get(0).chunkId()).isEqualTo("chunk-1");
        assertThat(answer.citations().get(0).source()).isEqualTo("refund-policy.txt");
        assertThat(answer.citations().get(0).chunkIndex()).isEqualTo(3);
        assertThat(answer.citations().get(0).rerankScore()).isEqualTo(0.93);
        // 当前没有真实证据分级能力，不得伪造
        assertThat(answer.evidenceLevel()).isNull();
    }

    @Test
    void emptyResultsShouldReturnChineseErrorWithoutFabrication() throws Exception {
        var rag = mock(RerankedRagService.class);
        when(rag.search(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        PolicyAnswer answer = new PolicyTools(rag).refundPolicyRAG("特殊会员终身退款规则？");

        assertThat(answer.context()).isNull();
        assertThat(answer.citations()).isNull();
        assertThat(answer.error()).isEqualTo("当前政策知识库中没有检索到足够的信息。");
    }

    @Test
    void retrievalFailureShouldReturnChineseErrorNotThrow() throws Exception {
        var rag = mock(RerankedRagService.class);
        when(rag.search(anyString(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("PgVector 不可用"));

        PolicyAnswer answer = new PolicyTools(rag).refundPolicyRAG("退货期限是多少天？");

        assertThat(answer.error()).isEqualTo("当前政策知识库中没有检索到足够的信息。");
        assertThat(answer.context()).isNull();
    }
}
