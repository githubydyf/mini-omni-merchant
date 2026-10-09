package com.omnimerchant.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.omnimerchant.agent.entity.AgentStep;
import org.apache.ibatis.annotations.Mapper;

/** Agent 运行步骤 Mapper（沿用当前 MyBatis-Plus）。 */
@Mapper
public interface AgentStepMapper extends BaseMapper<AgentStep> {
}
