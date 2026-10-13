package com.omnimerchant.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 客户确认前的候选地址，不是审批申请。 */
@Data
@TableName("address_change_draft")
public class AddressChangeDraft {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String draftUuid;
    private String conversationUuid;
    private Long orderId;
    private Integer orderVersion;
    private String verifiedIdentity;
    private String rawInput;
    private String normalizedAddress;
    private String inputHash;
    private Long confirmedRequestId;
    private LocalDateTime expiresAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
}
