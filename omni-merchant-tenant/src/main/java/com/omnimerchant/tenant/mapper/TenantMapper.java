package com.omnimerchant.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.omnimerchant.tenant.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户表 Mapper，继承 MyBatis-Plus BaseMapper 获得通用 CRUD。
 *
 * <p>{@code tenant} 是全局身份/租户定义表，没有 tenant_id，已在
 * {@code StrictTenantLineHandler} 的忽略列表中，不参与租户 SQL 自动隔离。
 */
@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
}
