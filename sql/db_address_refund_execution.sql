-- 增量迁移，不覆盖历史订单、审批或失败审计；MySQL 5.7 可重复执行。
CREATE TABLE IF NOT EXISTS address_change_draft (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT UNSIGNED NOT NULL,
 draft_uuid VARCHAR(36) NOT NULL,
 conversation_uuid VARCHAR(36) NOT NULL,
 order_id BIGINT UNSIGNED NOT NULL,
 order_version INT NOT NULL,
 verified_identity VARCHAR(128) NOT NULL,
 raw_input TEXT NOT NULL COMMENT '客户原始输入，仅在受控审批数据中保留',
 normalized_address JSON NOT NULL,
 input_hash CHAR(64) NOT NULL,
 confirmed_request_id BIGINT UNSIGNED NULL,
 expires_at DATETIME(3) NOT NULL,
 confirmed_at DATETIME(3) NULL,
 created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_address_draft_uuid (tenant_id,draft_uuid),
 UNIQUE KEY uk_address_draft_input (tenant_id,conversation_uuid,input_hash),
 KEY idx_address_draft_conversation (tenant_id,conversation_uuid,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='买家地址确认草稿，不是已批准申请';
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='commerce_action_record' AND column_name='execution_mode')=0,
 'ALTER TABLE commerce_action_record ADD COLUMN execution_mode VARCHAR(32) NULL COMMENT ''本地退款固定为LOCAL_SIMULATION；历史保留空值''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='commerce_action_record' AND column_name='payment_refund_confirmed')=0,
 'ALTER TABLE commerce_action_record ADD COLUMN payment_refund_confirmed TINYINT(1) NULL COMMENT ''真实支付退款是否有凭据；本地执行为0，历史未知为空''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
