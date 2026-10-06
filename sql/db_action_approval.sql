-- ============================================================================
-- db_action_approval.sql —— 动作审批（Commerce Action Approval）模块
--
-- 复现自参考项目：
--   return_request          : db/migration/mysql/V2__platform_extensions.sql
--                             （= sql/db_extensions.sql）
--   commerce_action_request : db/migration/mysql/V9__shopify_connector.sql
--                             （= sql/db_shopify_v2.sql；id 自增见 V23）
--   commerce_action_policy  : db/migration/mysql/V6__commerce_actions.sql
--                             （= sql/db_actions.sql）
--
-- 设计：两个来源（ReturnRequest / CommerceActionRequest）**不合并**，
-- 由 CommerceApprovalService 聚合成统一审批视图，靠 ActionRequestVO.source 区分。
--
-- 注意（ReturnRequest.status 数字含义）：
--   原项目 Service 与 DDL 注释/前端对 2/3 的含义不一致：
--     DDL 注释 & 前端 : 1 pending / 2 reviewing / 3 approved / 4 rejected / 5 done
--     原 Service      : 1 待审批 / 2 已批准 / 3 已拒绝 / 4 已执行
--   本工程采用 DDL 注释 + 前端这一套（有 2 票，且能让前端按钮正确禁用），
--   approve → 3，reject → 4。详见 CommerceApprovalService 注释。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- return_request (AI 创建的客户诉求：退货/退款/补发/改地址)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `return_request` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `request_no` VARCHAR(64) NOT NULL,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `request_type` VARCHAR(32) NOT NULL COMMENT 'RETURN/REFUND/REPLACEMENT/ADDRESS_CHANGE',
  `external_order_number` VARCHAR(64) NOT NULL,
  `customer_email` VARCHAR(128) DEFAULT NULL,
  `reason` VARCHAR(512) DEFAULT NULL,
  `requested_items` JSON DEFAULT NULL,
  `amount` DECIMAL(15,4) DEFAULT NULL,
  `currency` VARCHAR(8) DEFAULT NULL,
  `priority` TINYINT NOT NULL DEFAULT 2,
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1 pending 2 reviewing 3 approved 4 rejected 5 done',
  `approval_required_reason` VARCHAR(256) DEFAULT NULL,
  `resolution` VARCHAR(64) DEFAULT NULL,
  `resolution_note` TEXT DEFAULT NULL,
  `ext_attr` JSON DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_request_no` (`request_no`),
  KEY `idx_tenant_status` (`tenant_id`, `status`, `created_at`),
  KEY `idx_tenant_order` (`tenant_id`, `external_order_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI-created customer action requests';


-- ---------------------------------------------------------------------------
-- commerce_action_request (需审批的高风险外部电商写操作)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `commerce_action_request` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT NOT NULL,
  `request_no` VARCHAR(64) NOT NULL,
  `action_type` VARCHAR(64) NOT NULL,
  `platform` VARCHAR(32) NOT NULL DEFAULT 'shopify',
  `external_order_id` VARCHAR(128) DEFAULT NULL,
  `external_order_number` VARCHAR(64) DEFAULT NULL,
  `customer_email` VARCHAR(128) DEFAULT NULL,
  `requested_payload` TEXT DEFAULT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING_APPROVAL',
  `risk_reason` VARCHAR(512) DEFAULT NULL,
  `approved_by` BIGINT DEFAULT NULL,
  `approved_at` DATETIME DEFAULT NULL,
  `executed_at` DATETIME DEFAULT NULL,
  `external_result` TEXT DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_action_request_no` (`request_no`),
  KEY `idx_action_tenant_status` (`tenant_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Approval-gated external commerce action request';


-- ---------------------------------------------------------------------------
-- commerce_action_policy (高风险动作审批闸门策略)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `commerce_action_policy` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `action_type` VARCHAR(32) NOT NULL COMMENT 'RETURN/REFUND/REPLACEMENT/ADDRESS_CHANGE/CANCEL_ORDER/COUPON',
  `approval_required` TINYINT(1) NOT NULL DEFAULT 1,
  `min_approver_role` VARCHAR(64) NOT NULL DEFAULT 'SUPPORT_SUPERVISOR',
  `amount_threshold` DECIMAL(15,4) DEFAULT NULL,
  `requires_identity_verification` TINYINT(1) NOT NULL DEFAULT 1,
  `idempotency_window_minutes` INT NOT NULL DEFAULT 60,
  `external_write_enabled` TINYINT(1) NOT NULL DEFAULT 0,
  `policy_note` VARCHAR(512) DEFAULT NULL,
  `active` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_action_policy` (`tenant_id`, `action_type`),
  KEY `idx_action_policy_active` (`tenant_id`, `active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Approval gate policy for high-risk commerce actions';


-- ============================================================================
-- 种子数据（幂等：先清空本模块三张表）
--
-- 全部关联现有真实订单 / 客户 / 会话：
--   tenant 1001: #1001-#1005 (ava/lucia/noah/emma/fraud)
--   tenant 1002: #2001-#2005 (kenji/maya/carlos/angry/li)
-- externalWriteEnabled 恒为 0：审批 ≠ 外部执行。
-- ============================================================================
DELETE FROM `return_request`;
DELETE FROM `commerce_action_request`;
DELETE FROM `commerce_action_policy`;

-- 策略：完整复刻原项目 demo_seed 的 6 条
INSERT INTO `commerce_action_policy`
  (`tenant_id`, `action_type`, `approval_required`, `min_approver_role`, `amount_threshold`,
   `requires_identity_verification`, `idempotency_window_minutes`, `external_write_enabled`, `policy_note`, `active`)
VALUES
  (1001, 'RETURN', 1, 'SUPPORT_AGENT', 0, 1, 60, 0, '退货只创建内部审批请求', 1),
  (1001, 'REFUND', 1, 'SUPPORT_SUPERVISOR', 50, 1, 120, 0, '退款不直接写 Shopify', 1),
  (1001, 'ADDRESS_CHANGE', 1, 'SUPPORT_AGENT', 0, 1, 60, 0, '改地址必须校验订单身份', 1),
  (1002, 'RETURN', 1, 'SUPPORT_AGENT', 0, 1, 60, 0, '电子产品退货需序列号/配件完整校验', 1),
  (1002, 'REFUND', 1, 'SUPPORT_SUPERVISOR', 0, 1, 120, 0, '退款不直接写 Shopify', 1),
  (1002, 'REPLACEMENT', 1, 'SUPPORT_SUPERVISOR', 0, 1, 120, 0, '补发需人工审批', 1);

-- 客户诉求：覆盖 RETURN/REFUND/REPLACEMENT/ADDRESS_CHANGE 与三种审批状态
-- 显式指定 id，保证可重复复现（自增不影响）
INSERT INTO `return_request`
  (`id`, `request_no`, `tenant_id`, `request_type`, `external_order_number`, `customer_email`, `reason`,
   `requested_items`, `amount`, `currency`, `priority`, `status`, `approval_required_reason`,
   `resolution`, `resolution_note`, `created_at`, `updated_at`)
VALUES
  (7001, 'RR-1001-RETURN', 1001, 'RETURN', '#1001', 'ava@example.com',
   '商品与描述不符，客户申请退货', JSON_ARRAY(JSON_OBJECT('sku','VL-EARBUD-PRO','qty',1)),
   93.9600, 'USD', 2, 1, '退货金额需人工确认后退回', NULL, NULL,
   '2026-09-26 10:10:00', '2026-09-26 10:10:00'),
  (7002, 'RR-1004-REFUND', 1001, 'REFUND', '#1004', 'emma@example.fr',
   '客户要求退款到原支付方式', JSON_ARRAY(JSON_OBJECT('sku','VL-WATCH-LITE','qty',1)),
   81.0000, 'EUR', 3, 3, '退款金额超过自动处理阈值', 'APPROVED_MANUAL', '主管已批准，等待财务处理',
   '2026-09-25 09:20:00', '2026-09-25 09:30:00'),
  (7003, 'RR-2005-RETURN', 1002, 'RETURN', '#2005', 'li@example.cn',
   '客户退回商品但配件缺失', JSON_ARRAY(JSON_OBJECT('sku','VL-GAN-65','qty',1)),
   69.0000, 'USD', 2, 4, '配件缺失需人工裁定', 'REJECTED', '配件不完整，拒绝退货申请',
   '2026-09-27 17:30:00', '2026-09-27 17:40:00'),
  (7004, 'RR-2004-REPLACEMENT', 1002, 'REPLACEMENT', '#2004', 'angry@example.com',
   '包裹派送异常，客户要求补发', JSON_ARRAY(JSON_OBJECT('sku','NS-BAG-28-BLK','qty',1)),
   129.5600, 'USD', 4, 1, '补发需人工审批', NULL, NULL,
   '2026-09-27 15:56:00', '2026-09-27 15:56:00'),
  (7005, 'RR-1002-ADDRESS', 1001, 'ADDRESS_CHANGE', '#1002', 'lucia@example.es',
   '客户申请修改收货地址', JSON_ARRAY(),
   0.0000, 'EUR', 1, 1, '改地址必须校验订单身份', NULL, NULL,
   '2026-09-26 11:40:00', '2026-09-26 11:40:00');

-- 高风险外部写操作：覆盖 PENDING_APPROVAL / APPROVED_MANUAL / REJECTED
INSERT INTO `commerce_action_request`
  (`id`, `tenant_id`, `request_no`, `action_type`, `platform`, `external_order_id`, `external_order_number`,
   `customer_email`, `requested_payload`, `status`, `risk_reason`, `approved_by`, `approved_at`,
   `executed_at`, `external_result`, `created_at`, `updated_at`)
VALUES
  (8001, 1002, 'AR-2003-CANCEL', 'CANCEL_ORDER', 'shopify', 'gid://shopify/Order/2003', '#2003',
   'carlos@example.mx', '{"orderNumber":"#2003","reason":"customer_request"}',
   'PENDING_APPROVAL', '取消订单为不可逆外部写操作', NULL, NULL, NULL, NULL,
   '2026-09-27 12:06:00', '2026-09-27 12:06:00'),
  (8002, 1002, 'AR-2001-REFUND', 'REFUND', 'shopify', 'gid://shopify/Order/2001', '#2001',
   'kenji@example.jp', '{"orderNumber":"#2001","amount":"141.00","currency":"USD"}',
   'APPROVED_MANUAL', '退款金额超过自动阈值', 1, '2026-09-27 09:15:00', NULL,
   'Manual approval recorded; no external ecommerce write was executed by AI.',
   '2026-09-27 09:10:00', '2026-09-27 09:15:00'),
  (8003, 1001, 'AR-1005-CANCEL', 'CANCEL_ORDER', 'shopify', 'gid://shopify/Order/1005', '#1005',
   'fraud@example.net', '{"orderNumber":"#1005","reason":"risk_hold"}',
   'REJECTED', '订单已取消，重复取消无意义', NULL, NULL, NULL, '风控拒绝：订单已处于取消状态',
   '2026-09-24 16:46:00', '2026-09-24 16:50:00');
