package com.omnimerchant.agent.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class CommerceDtos {

    private CommerceDtos() {
    }


    public record CustomerVO(
            Long id,
            String externalCustomerId,
            String email,
            String phone,
            String displayName,
            String countryCode,
            String languagePref,
            String customerTier,
            Integer totalOrders,
            BigDecimal totalSpent,
            LocalDateTime lastOrderAt,
            Integer isBlacklisted,
            LocalDateTime createdAt) {
    }



    public record OrderVO(
            Long id,
            String externalOrderId,
            String externalOrderNumber,
            String platform,
            String customerEmail,
            String customerName,
            String customerPhone,
            String orderStatus,
            String paymentStatus,
            String fulfillmentStatus,
            String currency,
            BigDecimal totalAmount,
            BigDecimal refundedAmount,
            String orderItems,
            String trackingNumber,
            String trackingCarrier,
            String trackingStatus,
            String trackingHistory,
            LocalDateTime estimatedDeliveryAt,
            LocalDateTime actualDeliveryAt,
            LocalDateTime placedAt,
            LocalDateTime updatedAt) {
    }


    public record ProductVO(
            Long id,
            String externalProductId,
            String handle,
            String title,
            String brand,
            String productType,
            String categoryL1,
            String categoryL2,
            String defaultSku,
            String currency,
            BigDecimal price,
            Integer totalStock,
            String stockStatus,
            String featuredImageUrl,
            BigDecimal ratingAvg,
            Integer ratingCount,
            Integer vectorSynced,
            Integer status,
            LocalDateTime updatedAt) {
    }

    /**
     * 内存分页结果（用于 Inbox 这类合并多来源后在内存中排序分页的场景）。
     * 与原项目 {@code CommerceDtos.PageResult} 保持一致。
     */
    public record PageResult<T>(long total, List<T> records) {
    }

}
