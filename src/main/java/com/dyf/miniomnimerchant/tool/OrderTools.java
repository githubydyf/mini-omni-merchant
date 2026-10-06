package com.dyf.miniomnimerchant.tool;

import com.dyf.miniomnimerchant.entity.OrderInfo;
import com.dyf.miniomnimerchant.service.OrderService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class OrderTools {

    private final OrderService orderService;

    public OrderTools(OrderService orderService) {
        this.orderService = orderService;
    }


    /**
     * 查询订单基本信息
     */
    @Tool(
            description = """
                    根据订单号查询真实订单信息，
                    包括订单状态、支付状态、订单金额、
                    发货状态和已有物流信息。

                    当用户询问某个具体订单的状态、
                    支付情况或订单事实时调用。

                    参数 orderNo 为订单编号。
                    """
    )
    public OrderInfo queryOrder(String orderNo) {



        return orderService
                .queryOrderByOrderNo(orderNo);
    }


    /**
     * 查询订单物流
     */
    @Tool(
            description = """
                    根据订单号查询订单物流信息，
                    包括物流公司、运单号、
                    当前订单状态和发货时间。

                    当用户询问某个具体订单是否发货、
                    使用什么物流、物流单号等信息时调用。

                    参数 orderNo 为订单编号。
                    """
    )
    public String trackLogistics(String orderNo) {



        OrderInfo order =
                orderService
                        .queryOrderByOrderNo(orderNo);


        /*
         * 订单不存在
         */
        if (order == null) {
            return "未找到订单：" + orderNo;
        }


        /*
         * 尚未生成物流信息
         */
        if (order.getTrackingNumber() == null
                || order.getTrackingNumber().isBlank()) {

            return """
                    订单号：%s
                    当前订单状态：%s
                    当前暂无物流信息，订单可能尚未发货。
                    """.formatted(
                    order.getOrderNo(),
                    order.getOrderStatus()
            );
        }


        /*
         * 已存在物流信息
         */
        return """
                订单号：%s
                物流公司：%s
                运单号：%s
                订单状态：%s
                发货时间：%s
                """.formatted(
                order.getOrderNo(),
                order.getTrackingCarrier(),
                order.getTrackingNumber(),
                order.getOrderStatus(),
                order.getShippedAt()
        );
    }
}