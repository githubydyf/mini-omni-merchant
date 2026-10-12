package com.omnimerchant.tenant.dto;

import lombok.Data;

/**
 * 更新租户请求 DTO。
 *
 * <p>不含 {@code tenantCode}（租户编码创建后不可修改），其余为可选增量字段。
 */
@Data
public class TenantUpdateDTO {

    private String storeName;
    private String storeLogoUrl;
    private String storeDescription;
    private String industry;
    private String countryCode;
    private String timezone;
    private String currency;

    private String ownerName;
    private String ownerEmail;
    private String ownerPhone;
    private String ownerCountry;

    private String defaultLang;
    private String subscriptionPlan;
    private Long monthlyTokenBudget;
    private Integer monthlyMessageQuota;
    private Integer qpsLimit;
    private Integer concurrentSessionLimit;

    private Integer status;
    private String statusReason;
    private String remark;
}
