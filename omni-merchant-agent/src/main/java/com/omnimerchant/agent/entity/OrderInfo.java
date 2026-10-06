package com.omnimerchant.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("order_info")
public class OrderInfo {

    @TableId(type = IdType.AUTO)
    private Long id;


    /* =========================
       租户 / 平台
       ========================= */

    private Long tenantId;

    private String externalOrderId;

    private String externalOrderNumber;

    private String platform;


    /* =========================
       客户
       ========================= */

    private Long customerId;

    private String externalCustomerId;

    private String customerEmail;

    private String customerName;

    private String customerPhone;


    /* =========================
       地址
       ========================= */

    private String shippingAddress;

    private String shippingCountry;

    private String shippingState;

    private String shippingZip;

    private String billingAddress;


    /* =========================
       状态
       ========================= */

    private String orderStatus;

    private String paymentStatus;

    private String fulfillmentStatus;


    /* =========================
       金额
       ========================= */

    private String currency;

    private BigDecimal subtotalAmount;

    private BigDecimal shippingAmount;

    private BigDecimal taxAmount;

    private BigDecimal discountAmount;

    private BigDecimal totalAmount;

    private BigDecimal refundedAmount;


    /* =========================
       商品
       ========================= */

    private String orderItems;

    private Integer itemCount;

    private Integer totalQuantity;


    /* =========================
       物流
       ========================= */

    private String trackingNumber;

    private String trackingNumbers;

    private String trackingCarrier;

    private String trackingUrl;

    private String trackingStatus;

    private String trackingHistory;

    private LocalDateTime trackingUpdatedAt;

    private LocalDateTime estimatedDeliveryAt;

    private LocalDateTime actualDeliveryAt;


    /* =========================
       标签 / 优惠
       ========================= */

    private String tags;

    private String discountCodes;

    private String note;


    /* =========================
       时间节点
       ========================= */

    private LocalDateTime placedAt;

    private LocalDateTime paidAt;

    private LocalDateTime shippedAt;

    private LocalDateTime cancelledAt;


    /* =========================
       同步
       ========================= */

    private LocalDateTime syncedAt;

    private String syncSource;

    private Integer syncVersion;


    /* =========================
       系统字段
       ========================= */

    private String extAttr;


    @TableLogic
    private Integer isDeleted;


    @Version
    private Integer version;


    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;


    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}