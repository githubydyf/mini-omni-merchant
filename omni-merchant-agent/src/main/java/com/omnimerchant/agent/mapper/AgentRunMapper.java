package com.omnimerchant.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.omnimerchant.agent.entity.AgentRun;
import org.apache.ibatis.annotations.Mapper;

/** Agent 运行记录 Mapper（沿用当前 MyBatis-Plus）。 */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {
}
