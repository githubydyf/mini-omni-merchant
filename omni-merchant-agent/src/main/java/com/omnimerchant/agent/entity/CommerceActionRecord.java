package com.omnimerchant.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 不可变的审批与执行记录；成功执行具有独立唯一键。 */
@Data
@TableName("commerce_action_record")
public class CommerceActionRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String source;
    private Long requestId;
    private String actionType;
    private String eventType;
    private String state;
    private String executionKey;
    private Long actorId;
    private Long orderId;
    private String note;
    private String beforeSnapshot;
    private String afterSnapshot;
    private LocalDateTime createdAt;
}
