package com.omnimerchant.knowledge.service.rerank;

import com.omnimerchant.knowledge.dto.RerankResult;

import java.util.List;

/**
 * Reranker 执行结果：既包含排序后的片段，也包含本次是否真正经过重排。
 *
 * <p>{@code mode} 取值：
 * <ul>
 *   <li>{@link #MODE_RERANKED}：Reranker 正常返回，{@code rerankScore} 为真实相关性分数。</li>
 *   <li>{@link #MODE_FALLBACK_EMPTY}：候选为空，无需重排。</li>
 *   <li>{@link #MODE_FALLBACK_ERROR}：Reranker 不可用，已降级为召回排名，
 *       {@code rerankScore} 为 0（表示未经重排，不是“相关性为 0”）。</li>
 * </ul>
 *
 * <p>证据评估必须依赖 {@code mode}：降级时不能把 0 分当作“证据很弱”，
 * 否则一次 Reranker 故障会让所有政策查询都被误判为无证据。
 */
public record RerankOutcome(List<RerankResult> results, String mode) {

    public static final String MODE_RERANKED = "reranked";
    public static final String MODE_FALLBACK_EMPTY = "fallback-empty";
    public static final String MODE_FALLBACK_ERROR = "fallback-error";

    /** 是否真正经过 Reranker 重排（true 时 rerankScore 才可用于判断证据强度）。 */
    public boolean reranked() {
        return MODE_RERANKED.equals(mode);
    }
}
