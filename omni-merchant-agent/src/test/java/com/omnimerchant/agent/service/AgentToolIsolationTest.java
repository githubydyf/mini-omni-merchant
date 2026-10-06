package com.omnimerchant.agent.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tool 隔离验收测试（对应任务 §9 / §26）。
 *
 * <p>验证 SpecialistPlan.toolAllowlist 通过 {@link AgentExecutionGuardService} 在
 * Java 层真实生效：某个 intent 下，模型能看到的 Tool 集合必须严格等于白名单，
 * 越权 Tool 不得出现在返回的 callbacks 中。
 */
class AgentToolIsolationTest {

    private final AgentOrchestratorService orchestrator = new AgentOrchestratorService();
    private final AgentExecutionGuardService guard = new AgentExecutionGuardService();

    /** 当前已注册的全部业务 Tool（与 ToolCallbackConfig 一致）。 */
    private final ToolCallback[] allCallbacks = {
            stub("queryOrder"),
            stub("trackLogistics"),
            stub("searchProductCatalog"),
            stub("createReturnRequest"),
            stub("requestRefundOrReplacement"),
            stub("requestAddressChange"),
            stub("escalateToHuman")
    };

    @Test
    void orderStatusShouldOnlyExposeQueryOrder() {
        var plan = orchestrator.plan("ORDER_STATUS", "请查一下订单 #1001 的状态");
        var names = guardedNames(plan);

        assertThat(names).containsExactly("queryOrder");
        assertThat(names).doesNotContain("trackLogistics", "searchProductCatalog",
                "createReturnRequest", "requestRefundOrReplacement", "requestAddressChange");
    }

    @Test
    void angryOrderStatusShouldAlsoExposeEscalation() {
        var plan = orchestrator.plan("ORDER_STATUS", "我很生气，这订单到底怎么回事！");
        var names = guardedNames(plan);

        assertThat(names).containsExactlyInAnyOrder("queryOrder", "escalateToHuman");
    }

    @Test
    void logisticsShouldExposeQueryAndTrackingOnly() {
        var plan = orchestrator.plan("LOGISTICS", "我的包裹到哪里了？");
        var names = guardedNames(plan);

        assertThat(names).containsExactlyInAnyOrder("queryOrder", "trackLogistics");
        assertThat(names).doesNotContain("createReturnRequest", "requestRefundOrReplacement",
                "requestAddressChange", "searchProductCatalog");
    }

    @Test
    void productAdviceShouldOnlyExposeCatalogSearch() {
        var plan = orchestrator.plan("PRODUCT_ADVICE", "推荐一个 100 美元以内的防水背包");
        var names = guardedNames(plan);

        assertThat(names).containsExactly("searchProductCatalog");
        assertThat(names).doesNotContain("queryOrder", "createReturnRequest",
                "requestRefundOrReplacement", "requestAddressChange", "escalateToHuman");
    }

    @Test
    void humanRequestShouldOnlyExposeEscalation() {
        var plan = orchestrator.plan("HUMAN_REQUEST", "请帮我转人工客服");
        var names = guardedNames(plan);

        assertThat(names).containsExactly("escalateToHuman");
        assertThat(names).doesNotContain("queryOrder", "searchProductCatalog");
    }

    @Test
    void returnRefundShouldExposeAfterSalesTools() {
        var plan = orchestrator.plan("RETURN_REFUND", "我想申请退款");
        var names = guardedNames(plan);

        assertThat(names).containsExactlyInAnyOrder("queryOrder", "createReturnRequest",
                "requestRefundOrReplacement", "requestAddressChange", "escalateToHuman");
        // 售后链路不应出现商品检索
        assertThat(names).doesNotContain("searchProductCatalog");
    }

    @Test
    void orderStatusMustNeverExposeRefundTool() {
        var plan = orchestrator.plan("ORDER_STATUS", "订单状态");
        assertThat(guardedNames(plan)).doesNotContain("requestRefundOrReplacement");
    }

    @Test
    void unknownIntentShouldFallBackToEmptyTriageAllowlist() {
        var plan = orchestrator.plan("SOMETHING_UNKNOWN", "随便说点什么");
        assertThat(plan.specialistKey()).isEqualTo("triage");
        assertThat(plan.toolAllowlist()).isEmpty();
    }

    @Test
    void policyQaShouldKeepDesignButProvideNoFakeTool() {
        var plan = orchestrator.plan("POLICY_QA", "退货运费谁承担？");
        assertThat(plan.specialistKey()).isEqualTo("policy_rag");
        // refundPolicyRAG 尚未复现，白名单里只有设计名，不存在假实现
        assertThat(plan.toolAllowlist()).containsExactly("refundPolicyRAG");
        assertThatThrownBy(() -> guard.guardedCallbacks(allCallbacks, plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refundPolicyRAG");
    }

    private List<String> guardedNames(AgentOrchestratorService.SpecialistPlan plan) {
        return guard.guardedCallbacks(allCallbacks, plan).stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    /** 构造一个只暴露名称的桩 ToolCallback，用于验证过滤逻辑。 */
    private ToolCallback stub(String name) {
        var definition = ToolDefinition.builder()
                .name(name)
                .description(name + " 测试桩")
                .inputSchema("{\"type\":\"object\"}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return "{}";
            }
        };
    }
}
