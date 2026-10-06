package com.dyf.miniomnimerchant.tool;

import com.dyf.miniomnimerchant.entity.ProductInfo;
import com.dyf.miniomnimerchant.service.ProductService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProductTools {

    private final ProductService productService;

    public ProductTools(ProductService productService) {
        this.productService = productService;
    }


    @Tool(
        description = """
                根据商品名称或关键词搜索商品。
                当用户询问某类商品、推荐商品、
                商品价格、库存或商品信息时调用此工具。
                """
    )
    public List<ProductInfo> searchProduct(String keyword) {

        return productService.searchProducts(keyword);
    }


    @Tool(
        description = """
                根据商品SKU查询商品详细信息。
                当用户明确提供SKU时使用。
                """
    )
    public ProductInfo queryProductBySku(String sku) {

        return productService.queryProductBySku(sku);
    }
}