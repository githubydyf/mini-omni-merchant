-- ============================================================================
-- db_escalation_ticket.sql —— 人工升级 + 工单模块
--
-- 复现自参考项目：
--   escalation_record : omni-merchant-bootstrap/src/main/resources/db/migration/mysql/V1__core_schema.sql
--   ticket            : omni-merchant-bootstrap/src/main/resources/db/migration/mysql/V4__helpdesk.sql
--
-- 说明：
--   1. 两层模型保持不变：conversation → escalation_record → ticket。
--      escalation_record 记录“为什么升级”，ticket 是由它投影得到的人工处理对象，
--      两者不合并。ticket(source_type='ESCALATION', source_id=escalation_record.id)。
--   2. 本阶段只创建本模块需要的两张表；V4 中的 ticket_note / support_macro /
--      agent_idempotency_guard 属于后续模块，暂不创建。
--   3. 种子数据只写 escalation_record（真实关联现有 conversation/customer），
--      ticket 由 HelpdeskProjectionService 在启动后投影生成，以真实验证投影逻辑
--      与幂等性（uk_ticket_source 保证同一 escalation 不会重复建单）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- escalation_record (人工升级记录表)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `escalation_record` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT  COMMENT '工单ID',
  `ticket_no`           VARCHAR(64)     NOT NULL                  COMMENT '工单编号(对外展示)',
  `tenant_id`           BIGINT UNSIGNED NOT NULL                  COMMENT '租户ID',
  `conversation_uuid`   VARCHAR(64)     NOT NULL                  COMMENT '会话UUID',
  `customer_id`         BIGINT UNSIGNED DEFAULT NULL              COMMENT '客户ID',

  `escalation_type`     VARCHAR(32)     NOT NULL                  COMMENT 'AUTO_AI/AI_PROACTIVE/USER_REQUEST',
  `escalation_reason`   VARCHAR(64)     NOT NULL                  COMMENT 'LOW_CONFIDENCE/AMOUNT_LIMIT/NEGATIVE_SENTIMENT/MAX_ITER/TOOL_FAILURE/USER_REQUEST',
  `reason_detail`       VARCHAR(1024)   DEFAULT NULL              COMMENT '详细原因',
  `confidence_score`    DECIMAL(4,3)    DEFAULT NULL              COMMENT '触发时的置信度',
  `sentiment_score`     DECIMAL(4,3)    DEFAULT NULL              COMMENT '触发时的情感分',
  `involved_amount`     DECIMAL(15,4)   DEFAULT NULL              COMMENT '涉及金额',
  `currency`            VARCHAR(8)      DEFAULT NULL,
  `summary`             TEXT            DEFAULT NULL              COMMENT 'AI 生成的会话摘要(给人工看)',
  `customer_intent`     VARCHAR(32)     DEFAULT NULL              COMMENT '客户主诉意图',

  `priority`            TINYINT         NOT NULL DEFAULT 2        COMMENT '1低 2中 3高 4紧急',
  `assigned_agent_id`   BIGINT UNSIGNED DEFAULT NULL              COMMENT '分配的客服ID',
  `assigned_at`         DATETIME(3)     DEFAULT NULL              COMMENT '分配时间',
  `assignment_strategy` VARCHAR(32)     DEFAULT NULL              COMMENT 'AUTO_LEAST_BUSY/MANUAL/SKILL_MATCH',

  `sla_response_seconds` INT            NOT NULL DEFAULT 300      COMMENT 'SLA 响应时长',
  `sla_resolve_seconds`  INT            NOT NULL DEFAULT 3600     COMMENT 'SLA 解决时长',
  `sla_response_due_at`  DATETIME       DEFAULT NULL              COMMENT 'SLA 响应截止时间',
  `sla_resolve_due_at`   DATETIME       DEFAULT NULL              COMMENT 'SLA 解决截止时间',
  `sla_response_breached` TINYINT(1)    NOT NULL DEFAULT 0        COMMENT '响应是否违约',
  `sla_resolve_breached`  TINYINT(1)    NOT NULL DEFAULT 0        COMMENT '解决是否违约',

  `status`              TINYINT         NOT NULL DEFAULT 1        COMMENT '1待分配 2待响应 3处理中 4已解决 5已关闭 6已取消',
  `first_response_at`   DATETIME(3)     DEFAULT NULL              COMMENT '首次响应时间',
  `resolved_at`         DATETIME(3)     DEFAULT NULL              COMMENT '解决时间',
  `closed_at`           DATETIME(3)     DEFAULT NULL              COMMENT '关闭时间',
  `resolution`          VARCHAR(32)     DEFAULT NULL              COMMENT '解决方式:RESOLVED/REFUNDED/REPLACED/EXPLAINED/UNRESOLVED',
  `resolution_note`     TEXT            DEFAULT NULL              COMMENT '解决备注',

  `csat_score`          TINYINT         DEFAULT NULL              COMMENT '客户满意度',
  `csat_comment`        VARCHAR(1024)   DEFAULT NULL,

  `parent_ticket_id`    BIGINT UNSIGNED DEFAULT NULL              COMMENT '父工单ID(二次升级)',
  `escalated_back_to_ai` TINYINT(1)     NOT NULL DEFAULT 0        COMMENT '是否再次交回 AI',

  `tags`                JSON            DEFAULT NULL,
  `ext_attr`            JSON            DEFAULT NULL,
  `version`             INT             NOT NULL DEFAULT 0,
  `created_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_no`         (`ticket_no`),
  KEY        `idx_tenant_status`    (`tenant_id`, `status`, `priority`, `created_at`)   COMMENT '工单池查询',
  KEY        `idx_assigned`         (`assigned_agent_id`, `status`)                     COMMENT '客服我的工单',
  KEY        `idx_conversation`     (`conversation_uuid`)                               COMMENT '会话工单关联',
  KEY        `idx_sla_breach`       (`tenant_id`, `sla_response_breached`, `sla_resolve_breached`)  COMMENT 'SLA 监控',
  KEY        `idx_priority_created` (`priority`, `created_at`)                          COMMENT '紧急工单优先'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人工升级工单表';


-- ---------------------------------------------------------------------------
-- ticket (统一客服工单表)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ticket` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `ticket_no` VARCHAR(64) NOT NULL,
  `conversation_uuid` VARCHAR(64) DEFAULT NULL,
  `source_type` VARCHAR(32) NOT NULL DEFAULT 'ESCALATION',
  `source_id` BIGINT UNSIGNED DEFAULT NULL,
  `channel` VARCHAR(32) DEFAULT NULL,
  `customer_id` BIGINT UNSIGNED DEFAULT NULL,
  `customer_email` VARCHAR(128) DEFAULT NULL,
  `subject` VARCHAR(256) NOT NULL,
  `summary` TEXT DEFAULT NULL,
  `intent` VARCHAR(64) DEFAULT NULL,
  `priority` TINYINT NOT NULL DEFAULT 2,
  `status` VARCHAR(32) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ASSIGNED/WAITING_CUSTOMER/PENDING_APPROVAL/RESOLVED/CLOSED/CANCELLED',
  `assigned_agent_id` BIGINT UNSIGNED DEFAULT NULL,
  `assigned_at` DATETIME(3) DEFAULT NULL,
  `first_response_at` DATETIME(3) DEFAULT NULL,
  `resolved_at` DATETIME(3) DEFAULT NULL,
  `closed_at` DATETIME(3) DEFAULT NULL,
  `sla_response_due_at` DATETIME(3) DEFAULT NULL,
  `sla_resolve_due_at` DATETIME(3) DEFAULT NULL,
  `sla_state` VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
  `csat_score` TINYINT DEFAULT NULL,
  `csat_comment` VARCHAR(512) DEFAULT NULL,
  `close_reason` VARCHAR(128) DEFAULT NULL,
  `tags` JSON DEFAULT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_no` (`tenant_id`, `ticket_no`),
  UNIQUE KEY `uk_ticket_source` (`tenant_id`, `source_type`, `source_id`),
  KEY `idx_ticket_status` (`tenant_id`, `status`, `priority`, `updated_at`),
  KEY `idx_ticket_assignee` (`tenant_id`, `assigned_agent_id`, `status`),
  KEY `idx_ticket_conversation` (`tenant_id`, `conversation_uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Unified helpdesk ticket independent from escalation_record';


-- ============================================================================
-- 种子数据（幂等：先清空本模块两张表）
--
-- 只写 escalation_record；ticket 由应用启动后的投影服务生成。
-- 全部真实关联现有 conversation / customer（conv-e1002-*、conv-f1001-*）。
-- 覆盖三种工单状态：OPEN(escalation.status=1) / ASSIGNED(=3) / RESOLVED(=4)。
-- ============================================================================
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM `ticket`;
DELETE FROM `escalation_record`;
SET FOREIGN_KEY_CHECKS = 1;

INSERT INTO `escalation_record`
(`id`, `ticket_no`, `tenant_id`, `conversation_uuid`, `customer_id`,
 `escalation_type`, `escalation_reason`, `reason_detail`, `confidence_score`, `sentiment_score`,
 `involved_amount`, `currency`, `summary`, `customer_intent`,
 `priority`, `assigned_agent_id`, `assigned_at`, `assignment_strategy`,
 `sla_response_seconds`, `sla_resolve_seconds`, `sla_response_due_at`, `sla_resolve_due_at`,
 `sla_response_breached`, `sla_resolve_breached`,
 `status`, `first_response_at`, `resolved_at`, `closed_at`, `resolution`, `resolution_note`,
 `escalated_back_to_ai`, `tags`, `version`, `created_at`, `updated_at`)
VALUES
-- 1) 物流异常 + 客户明确要求人工 → OPEN
(6001,'TKT-ESC-0001',1002,'conv-e1002-0004',2014,
 'USER_REQUEST','USER_REQUEST','客户明确要求人工处理承运商派送异常',0.420,-0.850,
 129.5600,'USD','订单 #2004 物流显示派送异常且已二次延误，客户要求人工介入联系承运商并给出明确处理方案。','LOGISTICS',
 4,NULL,NULL,NULL,
 300,3600,'2026-09-27 15:55:00','2026-09-27 16:45:00',
 0,0,
 1,NULL,NULL,NULL,NULL,NULL,
 0,JSON_ARRAY('delivery_exception','urgent'),0,'2026-09-27 15:50:00','2026-09-27 15:50:00'),

-- 2) 订单处理进度咨询升级 → ASSIGNED（已分配客服）
(6002,'TKT-ESC-0002',1002,'conv-e1002-0003',2013,
 'AUTO_AI','LOW_CONFIDENCE','AI 无法确认订单 #2003 的具体发货时间，置信度不足',0.380,0.050,
 59.0000,'USD','客户询问订单 #2003 处理进度，需要人工确认发货时间。','ORDER_QUERY',
 3,1,'2026-09-27 12:10:00','MANUAL',
 300,3600,'2026-09-27 12:10:00','2026-09-27 13:00:00',
 0,0,
 3,NULL,NULL,NULL,NULL,NULL,
 0,JSON_ARRAY('order_progress'),0,'2026-09-27 12:05:00','2026-09-27 12:10:00'),

-- 3) 退货流程咨询升级 → ASSIGNED（已分配客服）
(6003,'TKT-ESC-0003',1002,'conv-e1002-0005',2015,
 'USER_REQUEST','USER_REQUEST','客户要求人工协助办理退货与退款到账确认',0.550,-0.100,
 69.0000,'USD','客户咨询订单 #2005 退货流程及退款到账时间，人工介入处理。','REFUND',
 2,1,'2026-09-27 17:28:00','MANUAL',
 300,3600,'2026-09-27 17:28:00','2026-09-27 18:18:00',
 0,0,
 3,NULL,NULL,NULL,NULL,NULL,
 0,JSON_ARRAY('refund','vip'),0,'2026-09-27 17:26:00','2026-09-27 17:28:00'),

-- 4) 退款金额超限 → RESOLVED（已解决）
(6004,'TKT-ESC-0004',1001,'conv-f1001-0004',2004,
 'AUTO_AI','AMOUNT_LIMIT','退款金额超过 AI 自动处理上限，需人工审核确认',0.910,-0.400,
 199.0000,'USD','客户就订单 #1004 退款到账时间提出疑问，已人工核实并确认退款处理完成。','REFUND',
 2,1,'2026-09-25 09:18:00','MANUAL',
 300,3600,'2026-09-25 09:18:00','2026-09-25 10:08:00',
 0,0,
 4,'2026-09-25 09:20:00','2026-09-25 09:40:00','2026-09-25 09:40:00','EXPLAINED','已确认退款已原路退回，向客户说明银行到账时间。',
 0,JSON_ARRAY('refund','amount_limit'),0,'2026-09-25 09:16:00','2026-09-25 09:40:00');
