package com.omnimerchant.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.omnimerchant.agent.entity.AgentIdempotencyGuard;
import org.apache.ibatis.annotations.Mapper;

/** 副作用工具幂等 Mapper（沿用当前 MyBatis-Plus）。 */
@Mapper
public interface AgentIdempotencyGuardMapper extends BaseMapper<AgentIdempotencyGuard> {
}
