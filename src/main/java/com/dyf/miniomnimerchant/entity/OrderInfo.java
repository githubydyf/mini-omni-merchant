package com.dyf.miniomnimerchant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("order_info")
public class OrderInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private String customerEmail;

    private String orderStatus;

    private String paymentStatus;

    private String currency;

    private BigDecimal totalAmount;

    private String trackingNumber;

    private String trackingCarrier;

    private LocalDateTime placedAt;

    private LocalDateTime shippedAt;

    private LocalDateTime estimatedDeliveryAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}