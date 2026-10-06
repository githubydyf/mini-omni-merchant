package com.dyf.miniomnimerchant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dyf.miniomnimerchant.entity.ProductInfo;
import com.dyf.miniomnimerchant.mapper.ProductMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class ProductService {

    private final ProductMapper productMapper;

    public ProductService(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    /**
     * 查询所有商品
     */
    public List<ProductInfo> listProducts() {

        return productMapper.selectList(
                new LambdaQueryWrapper<ProductInfo>()
                        .orderByAsc(ProductInfo::getId)
        );
    }


    /**
     * 根据商品ID查询
     */
    public ProductInfo queryProductById(Long id) {

        return productMapper.selectById(id);
    }


    /**
     * 根据 SKU 精确查询商品
     */
    public ProductInfo queryProductBySku(String sku) {

        return productMapper.selectOne(
                new LambdaQueryWrapper<ProductInfo>()
                        .eq(ProductInfo::getSku, sku)
        );
    }


    /**
     * 根据关键词搜索商品
     *
     * 搜索范围：
     * product_name
     * description
     */
    public List<ProductInfo> searchProducts(String keyword) {

        LambdaQueryWrapper<ProductInfo> wrapper =
                new LambdaQueryWrapper<>();

        if (StringUtils.hasText(keyword)) {

            wrapper.and(w ->
                    w.like(ProductInfo::getProductName, keyword)
                     .or()
                     .like(ProductInfo::getDescription, keyword)
            );
        }

        wrapper.orderByDesc(ProductInfo::getStock);

        return productMapper.selectList(wrapper);
    }


    /**
     * 查询有库存的商品
     */
    public List<ProductInfo> queryAvailableProducts() {

        return productMapper.selectList(
                new LambdaQueryWrapper<ProductInfo>()
                        .gt(ProductInfo::getStock, 0)
                        .orderByDesc(ProductInfo::getStock)
        );
    }


    /**
     * 创建商品
     */
    public ProductInfo createProduct(ProductInfo productInfo) {

        productMapper.insert(productInfo);

        return productInfo;
    }


    /**
     * 更新商品
     */
    public boolean updateProduct(ProductInfo productInfo) {

        int rows = productMapper.updateById(productInfo);

        return rows > 0;
    }


    /**
     * 删除商品
     */
    public boolean deleteProduct(Long id) {

        int rows = productMapper.deleteById(id);

        return rows > 0;
    }
}