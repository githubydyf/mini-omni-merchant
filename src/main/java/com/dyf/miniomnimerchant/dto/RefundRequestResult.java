package com.dyf.miniomnimerchant.dto;

public record RefundRequestResult(

        boolean success,

        String requestNo,

        String orderNo,

        String actionType,

        String status,

        String message

) {
}