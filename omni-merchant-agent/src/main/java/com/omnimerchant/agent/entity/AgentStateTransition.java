package com.omnimerchant.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话状态转换历史（只追加，不更新）。
 *
 * <p>复现自参考项目 {@code entity/AgentStateTransition}，表见 {@code sql/db_agent_state.sql}。
 * 每发生一次真实状态转换就新增一条；不允许通过更新历史来"伪造"新的转换。
 */
@Data
@TableName("agent_state_transition")
public class AgentStateTransition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private String conversationUuid;
    private String traceId;
    private String fromState;
    private String toState;
    private String triggerType;
    private String triggerName;
    private String reasonRedacted;
    private LocalDateTime createdAt;
}
