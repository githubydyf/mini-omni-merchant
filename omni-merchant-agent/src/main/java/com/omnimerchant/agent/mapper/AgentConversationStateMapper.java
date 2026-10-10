package com.omnimerchant.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.omnimerchant.agent.entity.AgentConversationState;
import org.apache.ibatis.annotations.Mapper;

/** 会话当前状态 Mapper（沿用当前 MyBatis-Plus）。 */
@Mapper
public interface AgentConversationStateMapper extends BaseMapper<AgentConversationState> {
}
