package com.dyf.miniomnimerchant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dyf.miniomnimerchant.entity.OrderInfo;
import com.dyf.miniomnimerchant.mapper.OrderMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OrderService {

    private final OrderMapper orderMapper;

    public OrderService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /**
     * 根据订单号查询订单
     */
    public OrderInfo queryOrderByOrderNo(String orderNo) {

        LambdaQueryWrapper<OrderInfo> wrapper =
                new LambdaQueryWrapper<>();


        wrapper.eq(OrderInfo::getOrderNo, orderNo);

        return orderMapper.selectOne(wrapper);
    }

    /**
     * 根据客户邮箱查询订单
     */
    public List<OrderInfo> queryOrdersByEmail(String email) {

        LambdaQueryWrapper<OrderInfo> wrapper =
                new LambdaQueryWrapper<>();

        wrapper.eq(OrderInfo::getCustomerEmail, email)
               .orderByDesc(OrderInfo::getPlacedAt);

        return orderMapper.selectList(wrapper);
    }

    /**
     * 根据ID查询
     */
    public OrderInfo queryById(Long id) {
        return orderMapper.selectById(id);
    }

    /**
     * 创建订单
     */
    public OrderInfo createOrder(OrderInfo orderInfo) {

        orderMapper.insert(orderInfo);

        return orderInfo;
    }

    /**
     * 修改订单
     */
    public boolean updateOrder(OrderInfo orderInfo) {

        int rows = orderMapper.updateById(orderInfo);

        return rows > 0;
    }

    /**
     * 删除订单
     */
    public boolean deleteOrder(Long id) {

        int rows = orderMapper.deleteById(id);

        return rows > 0;
    }
}