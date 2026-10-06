package com.dyf.miniomnimerchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dyf.miniomnimerchant.entity.OrderInfo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper extends BaseMapper<OrderInfo> {
}