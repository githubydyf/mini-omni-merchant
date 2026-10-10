package com.omnimerchant.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 副作用工具幂等登记。
 *
 * <p>复现自参考项目 {@code entity/AgentIdempotencyGuard}，表见 {@code sql/db_agent_idempotency.sql}。
 *
 * <p>{@code status} 语义（与参考项目一致）：
 * <ul>
 *   <li>{@code RECORDED} —— 已登记，业务 Tool 即将执行；</li>
 *   <li>{@code COMPLETED} —— 幂等保护下的内部 Tool 调用已完成（<b>不代表退款/补发/改地址成功</b>）；</li>
 *   <li>{@code FAILED} —— 执行抛异常；不自动重试，需人工确认。</li>
 * </ul>
 */
@Data
@TableName("agent_idempotency_guard")
public class AgentIdempotencyGuard {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private String conversationUuid;
    private String guardKey;
    private String toolName;
    private String requestHash;
    private String status;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
}
