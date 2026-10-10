package com.omnimerchant.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话当前工作流状态（每租户每会话一条）。
 *
 * <p>复现自参考项目 {@code entity/AgentConversationState}，表见 {@code sql/db_agent_state.sql}。
 * {@code version} 用于乐观锁：状态更新必须带版本条件，影响行数不为 1 即拒绝本次转换。
 */
@Data
@TableName("agent_conversation_state")
public class AgentConversationState {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private String conversationUuid;
    private String state;
    private String lastTraceId;
    private String lastReason;

    @Version
    private Integer version;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
