package com.omnimerchant.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Agent 运行记录：一次正式 Chat 调用对应一条。
 *
 * <p>复现自参考项目 {@code entity/AgentRun}，表结构见 {@code sql/db_agent_trace.sql}。
 *
 * <p><b>不伪造指标</b>：{@code promptTokens / completionTokens / costUsd /
 * retrievedDocCount / citationCount} 当前没有真实采集能力，保持 0 或 null，
 * 仅用于结构兼容，不代表真实测量结果。
 */
@Data
@TableName("agent_run")
public class AgentRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private String traceId;
    private String conversationUuid;
    private String runType;
    private String intent;
    private String modelProvider;
    private String modelName;
    private String routerDecision;
    private String inputRedacted;
    private String inputHash;
    private String finalAnswerRedacted;
    private String status;
    private String failureCategory;
    private String failureReason;
    private Long promptTokens;
    private Long completionTokens;
    private BigDecimal costUsd;
    private Integer firstTokenLatencyMs;
    private Integer totalLatencyMs;
    private Integer toolCallCount;
    private Integer retrievedDocCount;
    private Integer citationCount;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
