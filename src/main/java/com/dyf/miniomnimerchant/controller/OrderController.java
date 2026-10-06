package com.dyf.miniomnimerchant.controller;

import com.dyf.miniomnimerchant.entity.OrderInfo;
import com.dyf.miniomnimerchant.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 根据订单号查询订单
     *
     * GET /api/orders/ORD001
     */
    @GetMapping("/{orderNo}")
    public ResponseEntity<OrderInfo> queryOrder(
            @PathVariable String orderNo) {

        OrderInfo order =
                orderService.queryOrderByOrderNo(orderNo);

        if (order == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(order);
    }

    /**
     * 根据邮箱查询订单
     *
     * GET /api/orders?email=test@example.com
     */
    @GetMapping
    public List<OrderInfo> queryOrdersByEmail(
            @RequestParam String email) {

        return orderService.queryOrdersByEmail(email);
    }

    /**
     * 创建订单
     *
     * POST /api/orders
     */
    @PostMapping
    public OrderInfo createOrder(
            @RequestBody OrderInfo orderInfo) {

        return orderService.createOrder(orderInfo);
    }

    /**
     * 修改订单
     *
     * PUT /api/orders
     */
    @PutMapping
    public ResponseEntity<Void> updateOrder(
            @RequestBody OrderInfo orderInfo) {

        boolean success =
                orderService.updateOrder(orderInfo);

        if (!success) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok().build();
    }

    /**
     * 删除订单
     *
     * DELETE /api/orders/1
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOrder(
            @PathVariable Long id) {

        boolean success =
                orderService.deleteOrder(id);

        if (!success) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.noContent().build();
    }
}