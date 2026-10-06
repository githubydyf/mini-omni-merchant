package com.dyf.miniomnimerchant.controller;

import com.dyf.miniomnimerchant.entity.ProductInfo;
import com.dyf.miniomnimerchant.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }


    /**
     * 查询所有商品
     *
     * GET /api/products
     */
    @GetMapping
    public List<ProductInfo> listProducts() {

        return productService.listProducts();
    }


    /**
     * 根据ID查询商品
     *
     * GET /api/products/1
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProductInfo> queryProductById(
            @PathVariable Long id) {

        ProductInfo product =
                productService.queryProductById(id);

        if (product == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(product);
    }


    /**
     * 根据SKU查询
     *
     * GET /api/products/sku/SKU001
     */
    @GetMapping("/sku/{sku}")
    public ResponseEntity<ProductInfo> queryProductBySku(
            @PathVariable String sku) {

        ProductInfo product =
                productService.queryProductBySku(sku);

        if (product == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(product);
    }


    /**
     * 根据关键词搜索
     *
     * GET /api/products/search?keyword=Mouse
     */
    @GetMapping("/search")
    public List<ProductInfo> searchProducts(
            @RequestParam String keyword) {

        return productService.searchProducts(keyword);
    }


    /**
     * 创建商品
     */
    @PostMapping
    public ProductInfo createProduct(
            @RequestBody ProductInfo productInfo) {

        return productService.createProduct(productInfo);
    }


    /**
     * 修改商品
     */
    @PutMapping
    public ResponseEntity<Void> updateProduct(
            @RequestBody ProductInfo productInfo) {

        boolean success =
                productService.updateProduct(productInfo);

        if (!success) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok().build();
    }


    /**
     * 删除商品
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(
            @PathVariable Long id) {

        boolean success =
                productService.deleteProduct(id);

        if (!success) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.noContent().build();
    }
}