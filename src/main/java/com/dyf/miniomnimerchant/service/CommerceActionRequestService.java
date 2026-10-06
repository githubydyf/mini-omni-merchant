package com.dyf.miniomnimerchant.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dyf.miniomnimerchant.dto.RefundRequestResult;
import com.dyf.miniomnimerchant.entity.CommerceActionRequest;
import com.dyf.miniomnimerchant.entity.OrderInfo;
import com.dyf.miniomnimerchant.mapper.CommerceActionRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class CommerceActionRequestService {

    private final OrderService orderService;

    private final CommerceActionRequestMapper actionRequestMapper;


    public CommerceActionRequestService(
            OrderService orderService,
            CommerceActionRequestMapper actionRequestMapper
    ) {

        this.orderService = orderService;
        this.actionRequestMapper = actionRequestMapper;
    }


    /**
     * 创建退款申请。
     * <p>
     * 注意：
     * <p>
     * 这里只创建内部审批请求，
     * 不执行真实资金退款。
     */
    @Transactional
    public RefundRequestResult requestRefund(
            String orderNo,
            String reason
    ) {

        /*
         * =====================================================
         * 1. 参数检查
         * =====================================================
         */
        if (orderNo == null
                || orderNo.isBlank()) {

            return new RefundRequestResult(
                    false,
                    null,
                    null,
                    "REFUND",
                    null,
                    "订单号不能为空"
            );
        }


        if (reason == null
                || reason.isBlank()) {

            return new RefundRequestResult(
                    false,
                    null,
                    orderNo,
                    "REFUND",
                    null,
                    "请提供退款原因"
            );
        }


        /*
         * =====================================================
         * 2. 查询真实订单
         * =====================================================
         */
        OrderInfo order =
                orderService
                        .queryOrderByOrderNo(orderNo);


        if (order == null) {

            return new RefundRequestResult(
                    false,
                    null,
                    orderNo,
                    "REFUND",
                    null,
                    "未找到订单：" + orderNo
            );
        }


        /*
         * =====================================================
         * 3. 检查支付状态
         *
         * 没有支付过的订单没有资金可以退款。
         * =====================================================
         */
        if (!"paid".equalsIgnoreCase(
                order.getPaymentStatus())) {

            return new RefundRequestResult(
                    false,
                    null,
                    orderNo,
                    "REFUND",
                    null,
                    "当前订单支付状态为 "
                            + order.getPaymentStatus()
                            + "，不能创建退款申请"
            );
        }


        /*
         * =====================================================
         * 4. 已退款订单不能重复申请
         * =====================================================
         */
        if ("refunded".equalsIgnoreCase(
                order.getOrderStatus())) {

            return new RefundRequestResult(
                    false,
                    null,
                    orderNo,
                    "REFUND",
                    null,
                    "该订单已经完成退款"
            );
        }


        /*
         * =====================================================
         * 5. 检查是否已有 PENDING 退款申请
         *
         * 防止重复提交。
         * =====================================================
         */
        CommerceActionRequest existing =
                actionRequestMapper.selectOne(
                        Wrappers
                                .<CommerceActionRequest>
                                        lambdaQuery()

                                .eq(
                                        CommerceActionRequest::getOrderNo,
                                        orderNo
                                )

                                .eq(
                                        CommerceActionRequest::getActionType,
                                        "REFUND"
                                )

                                .eq(
                                        CommerceActionRequest::getStatus,
                                        "PENDING"
                                )

                                .last("LIMIT 1")
                );


        if (existing != null) {

            return new RefundRequestResult(
                    false,
                    existing.getRequestNo(),
                    orderNo,
                    "REFUND",
                    existing.getStatus(),
                    "该订单已经存在待处理的退款申请"
            );
        }


        /*
         * =====================================================
         * 6. 创建退款请求
         * =====================================================
         */
        CommerceActionRequest request =
                new CommerceActionRequest();


        String requestNo =
                generateRequestNo();


        request.setRequestNo(requestNo);

        request.setOrderNo(
                order.getOrderNo()
        );

        request.setCustomerEmail(
                order.getCustomerEmail()
        );

        request.setActionType(
                "REFUND"
        );

        request.setReason(
                reason
        );


        /*
         * 第一版先不让 LLM 自己决定退款金额。
         *
         * 后面实现部分退款时，
         * 再增加 requestedAmount。
         */
        request.setRequestedAmount(null);


        request.setStatus(
                "PENDING"
        );

        request.setCreatedAt(
                LocalDateTime.now()
        );

        request.setUpdatedAt(
                LocalDateTime.now()
        );


        /*
         * =====================================================
         * 7. 写入数据库
         * =====================================================
         */
        int rows =
                actionRequestMapper
                        .insert(request);


        if (rows <= 0) {

            return new RefundRequestResult(
                    false,
                    null,
                    orderNo,
                    "REFUND",
                    null,
                    "退款申请创建失败"
            );
        }


        /*
         * =====================================================
         * 8. 返回结果
         * =====================================================
         */
        return new RefundRequestResult(
                true,
                requestNo,
                orderNo,
                "REFUND",
                "PENDING",
                "退款申请已提交，当前等待审核"
        );
    }


    /**
     * 生成业务申请编号
     */
    private String generateRequestNo() {

        String time =
                LocalDateTime.now()
                        .format(
                                DateTimeFormatter.ofPattern(
                                        "yyyyMMddHHmmss"
                                )
                        );


        String random =
                UUID.randomUUID()
                        .toString()
                        .substring(0, 4)
                        .toUpperCase();


        return "ACT"
                + time
                + random;
    }
}