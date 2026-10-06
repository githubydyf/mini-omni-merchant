package com.dyf.miniomnimerchant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data
@TableName("commerce_action_request")
public class CommerceActionRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestNo;

    private String orderNo;

    private String customerEmail;

    /**
     * REFUND
     * REPLACEMENT
     * ADDRESS_CHANGE
     * CANCEL_ORDER
     */
    private String actionType;

    private String reason;

    /**
     * 申请退款金额。
     *
     * 当前可以为空，
     * 后面做部分退款时再使用。
     */
    private BigDecimal requestedAmount;

    /**
     * PENDING
     * APPROVED
     * REJECTED
     * COMPLETED
     */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;



}