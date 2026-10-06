package com.omnimerchant.agent.tool;

import com.omnimerchant.agent.service.CommercePlatformService;
import com.omnimerchant.agent.service.ToolAuditService;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI 工具：商品目录检索。
 *
 * <p>复现自参考项目 {@code tool/ProductTools}。查询逻辑保留在
 * {@link CommercePlatformService#searchProductCatalog}，工具只做转发 + 审计。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductTools {

    private final CommercePlatformService commerceService;
    private final ToolAuditService toolAuditService;

    @Tool(description = """
            检索本租户的商品目录，用于电商商品咨询。\
            当客户询问商品推荐、库存、价格、SKU、规格、类目匹配或购买建议时使用。\
            返回来自本租户缓存的结构化商品卡片。
            """)
    public ProductSearchResult searchProductCatalog(
            @ToolParam(description = "客户商品查询词或关键词") String query,
            @ToolParam(description = "可选的类目或商品类型", required = false) String category,
            @ToolParam(description = "可选的价格上限（店铺币种）", required = false) BigDecimal maxPrice,
            @ToolParam(description = "返回商品的最大数量", required = false) Integer limit) {
        if (TenantContextHolder.get() == null) {
            return new ProductSearchResult("MISSING_TENANT_CONTEXT", List.of(),
                    "商品查询需要已核验的租户上下文。");
        }
        return toolAuditService.record("searchProductCatalog",
                params("query", query, "category", category, "maxPrice", maxPrice),
                () -> {
                    var products = commerceService.searchProductCatalog(query, maxPrice, category,
                            limit == null ? 5 : limit);
                    return new ProductSearchResult(products.isEmpty() ? "NO_MATCH" : "OK", products,
                            products.isEmpty()
                                    ? "本租户商品目录中未找到匹配商品。"
                                    : "商品数据来自本租户商品目录缓存。");
                });
    }

    /** 把 key/value 交替的参数拼成有序 Map，用于审计日志。 */
    private Map<String, Object> params(Object... entries) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }

    /** 商品检索结果（字段与参考项目一致）。 */
    public record ProductSearchResult(
            String status,
            List<CommercePlatformService.ProductRecommendation> products,
            String message) {
    }
}
