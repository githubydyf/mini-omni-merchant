package com.omnimerchant.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.omnimerchant.agent.entity.AgentStateTransition;
import org.apache.ibatis.annotations.Mapper;

/** 会话状态转换历史 Mapper（沿用当前 MyBatis-Plus）。 */
@Mapper
public interface AgentStateTransitionMapper extends BaseMapper<AgentStateTransition> {
}
