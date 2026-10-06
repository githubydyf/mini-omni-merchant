package com.dyf.miniomnimerchant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("product_info")
public class ProductInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String productName;

    private String sku;

    private BigDecimal price;

    private String currency;

    private Integer stock;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}