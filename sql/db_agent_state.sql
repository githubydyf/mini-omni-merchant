-- ============================================================================
-- db_agent_state.sql —— Agent 执行状态机（agent_conversation_state / agent_state_transition）
--
-- 复现自参考项目：
--   sql/db_agent_runtime.sql
--   omni-merchant-bootstrap/src/main/resources/db/migration/mysql/V16__agent_runtime.sql
--
-- 说明：
--   1. 本文件只创建状态机两张表；conversation / agent_run / agent_step / tool_call_log
--      均已存在，不重复创建。
--   2. agent_conversation_state 保存"当前状态"（每租户每会话一行，唯一键约束）；
--      agent_state_transition 保存"每次真实转换的历史"（只追加，不更新）。
--   3. version 用于乐观锁（CAS 更新），与实体 @Version 对应。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `agent_conversation_state` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `tenant_id`         BIGINT UNSIGNED NOT NULL                             COMMENT '租户ID',
  `conversation_uuid` VARCHAR(64)     NOT NULL                             COMMENT '会话UUID',
  `state`             VARCHAR(48)     NOT NULL DEFAULT 'NEW'                COMMENT '当前状态机状态',
  `last_trace_id`     VARCHAR(64)     DEFAULT NULL                         COMMENT '最近一次转换的 traceId',
  `last_reason`       VARCHAR(256)    DEFAULT NULL                         COMMENT '最近一次转换原因(已脱敏)',
  `version`           INT             NOT NULL DEFAULT 0                   COMMENT '乐观锁版本',
  `created_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_conversation_state` (`tenant_id`, `conversation_uuid`),
  KEY `idx_agent_conversation_status` (`tenant_id`, `state`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='会话当前工作流状态(每租户每会话一条)';


CREATE TABLE IF NOT EXISTS `agent_state_transition` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `tenant_id`         BIGINT UNSIGNED NOT NULL                             COMMENT '租户ID',
  `conversation_uuid` VARCHAR(64)     NOT NULL                             COMMENT '会话UUID',
  `trace_id`          VARCHAR(64)     DEFAULT NULL                         COMMENT '触发本次转换的 traceId',
  `from_state`        VARCHAR(48)     NOT NULL                             COMMENT '转换前状态',
  `to_state`          VARCHAR(48)     NOT NULL                             COMMENT '转换后状态',
  `trigger_type`      VARCHAR(48)     NOT NULL                             COMMENT '触发类型:SUPERVISOR/ROUTER/TOOL/RESPONSE/FAILURE',
  `trigger_name`      VARCHAR(128)    DEFAULT NULL                         COMMENT '触发名称:specialist 或 toolName',
  `reason_redacted`   VARCHAR(512)    DEFAULT NULL                         COMMENT '转换原因(已脱敏)',
  `created_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`id`),
  KEY `idx_agent_transition_conversation` (`tenant_id`, `conversation_uuid`, `created_at`),
  KEY `idx_agent_transition_trace` (`tenant_id`, `trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='会话状态转换历史(只追加,可回放)';
