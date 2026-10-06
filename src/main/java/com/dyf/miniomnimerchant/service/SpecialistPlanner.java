package com.dyf.miniomnimerchant.service;

import com.dyf.miniomnimerchant.agent.IntentType;
import com.dyf.miniomnimerchant.agent.SpecialistPlan;
import org.springframework.stereotype.Service;

@Service
public class SpecialistPlanner {

    public SpecialistPlan plan(IntentType intent) {

        return switch (intent) {

            /*
             * 订单查询 Specialist
             *
             * 负责：
             * - 订单状态
             * - 支付状态
             * - 发货状态
             * - 物流查询
             */
            case ORDER_STATUS ->
                    new SpecialistPlan(
                            IntentType.ORDER_STATUS,
                            "order"
                    );


            /*
             * 退货 / 退款 Specialist
             *
             * 负责：
             * - 具体订单退货
             * - 具体订单退款
             * - 商品质量问题
             * - 错发 / 少发
             * - 补发
             */
            case RETURN_REFUND ->
                    new SpecialistPlan(
                            IntentType.RETURN_REFUND,
                            "return"
                    );


            /*
             * 商品 Specialist
             */
            case PRODUCT_ADVICE ->
                    new SpecialistPlan(
                            IntentType.PRODUCT_ADVICE,
                            "product"
                    );


            /*
             * 政策 Specialist
             */
            case POLICY_QA ->
                    new SpecialistPlan(
                            IntentType.POLICY_QA,
                            "policy"
                    );


            /*
             * 人工转接 Specialist
             */
            case HUMAN_REQUEST ->
                    new SpecialistPlan(
                            IntentType.HUMAN_REQUEST,
                            "handoff"
                    );


            /*
             * 普通客服
             */
            case GENERAL ->
                    new SpecialistPlan(
                            IntentType.GENERAL,
                            "general"
                    );
        };
    }
}