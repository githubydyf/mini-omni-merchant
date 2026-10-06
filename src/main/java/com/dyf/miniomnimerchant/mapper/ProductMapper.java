package com.dyf.miniomnimerchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dyf.miniomnimerchant.entity.ProductInfo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductMapper extends BaseMapper<ProductInfo> {
}