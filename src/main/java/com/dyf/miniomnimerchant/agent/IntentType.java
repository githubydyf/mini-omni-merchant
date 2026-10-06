package com.dyf.miniomnimerchant.agent;



public enum IntentType {

    /**
     * 订单状态、支付状态、物流查询
     */
    ORDER_STATUS,

    /**
     * 具体订单的退货、退款、换货、补发诉求
     */
    RETURN_REFUND,

    /**
     * 商品查询、价格、库存、推荐
     */
    PRODUCT_ADVICE,

    /**
     * 纯政策咨询
     */
    POLICY_QA,

    /**
     * 用户明确要求人工，或者需要升级处理
     */
    HUMAN_REQUEST,

    /**
     * 普通问题
     */
    GENERAL
}