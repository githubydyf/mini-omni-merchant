-- ============================================================================
-- db_agent_idempotency.sql —— 副作用 Tool 幂等表（agent_idempotency_guard）
--
-- 复现自参考项目：
--   sql/db_helpdesk.sql
--   omni-merchant-bootstrap/src/main/resources/db/migration/mysql/V4__helpdesk.sql
--
-- 作用：阻止同一会话对同一业务请求重复创建"副作用"记录
--       （退货申请 / 退款申请 / 改地址申请 / 人工升级）。
--
-- 核心：唯一键 uk_agent_guard(tenant_id, conversation_uuid, guard_key)
--       靠数据库原子性完成竞争，而不是 SELECT 后再 INSERT。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `agent_idempotency_guard` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT    COMMENT '主键',
  `tenant_id`         BIGINT UNSIGNED NOT NULL                   COMMENT '租户ID',
  `conversation_uuid` VARCHAR(64)     NOT NULL                   COMMENT '会话UUID',
  `guard_key`         VARCHAR(128)    NOT NULL                   COMMENT '幂等键:toolName:规范化参数哈希',
  `tool_name`         VARCHAR(128)    NOT NULL                   COMMENT '工具名',
  `request_hash`      VARCHAR(128)    NOT NULL                   COMMENT '规范化请求的 SHA-256',
  `status`            VARCHAR(32)     NOT NULL DEFAULT 'RECORDED' COMMENT 'RECORDED/COMPLETED/FAILED',
  `first_seen_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次登记时间',
  `last_seen_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近出现时间',

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_guard` (`tenant_id`, `conversation_uuid`, `guard_key`),
  KEY `idx_agent_guard_tool` (`tenant_id`, `tool_name`, `last_seen_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='副作用工具重复调用防护';
