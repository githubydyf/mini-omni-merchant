-- 增量迁移；不清空、不覆盖历史消息和业务数据。MySQL 5.7 可重复执行。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='chat_message' AND column_name='source')=0,
  'ALTER TABLE chat_message ADD COLUMN source VARCHAR(16) NULL COMMENT ''消息来源：USER/AI/HUMAN；历史未知保留空值''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='chat_message' AND column_name='sender_id')=0,
  'ALTER TABLE chat_message ADD COLUMN sender_id BIGINT UNSIGNED NULL COMMENT ''真实人工消息操作人''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS commerce_action_record (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT UNSIGNED NOT NULL,
  source VARCHAR(32) NOT NULL,
  request_id BIGINT UNSIGNED NOT NULL,
  action_type VARCHAR(32) NOT NULL,
  event_type VARCHAR(32) NOT NULL COMMENT 'APPROVED/REJECTED/EXECUTION',
  state VARCHAR(32) NOT NULL COMMENT 'APPROVED_MANUAL/REJECTED/EXECUTED/FAILED',
  execution_key VARCHAR(32) NULL COMMENT '成功执行固定为LOCAL；其余为空，允许审计多次失败',
  actor_id BIGINT UNSIGNED NOT NULL,
  order_id BIGINT UNSIGNED NULL,
  note VARCHAR(1000) NULL,
  before_snapshot JSON NULL,
  after_snapshot JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_action_execution (tenant_id, source, request_id, execution_key),
  KEY idx_action_record (tenant_id, source, request_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工审批与本地执行审计；不作为支付退款凭据';

INSERT IGNORE INTO role_permission (role_key, permission_key) VALUES
 ('TENANT_ADMIN','action:execute'), ('SUPPORT_SUPERVISOR','action:execute');
-- 只补充缺失的本地取消策略；不放开已有外部写开关。
INSERT IGNORE INTO commerce_action_policy
 (tenant_id,action_type,approval_required,min_approver_role,requires_identity_verification,external_write_enabled,policy_note,active)
 SELECT id,'CANCEL_ORDER',1,'SUPPORT_SUPERVISOR',1,0,'人工批准后由授权人员执行本地未发货订单取消；不执行支付退款',1 FROM tenant;
