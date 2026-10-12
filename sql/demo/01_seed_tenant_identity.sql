-- =====================================================================
-- 01_seed_tenant_identity.sql —— 演示租户（中文）+ 角色权限 + 后台演示身份
--
-- 幂等：全部使用 INSERT ... ON DUPLICATE KEY UPDATE（依赖唯一键）。
-- 安全：本文件只做 UPSERT，不删除任何数据。
--
-- 演示身份默认 **DISABLED**（不可登录）；启用方法见文件末尾说明与
-- docs/demo-zh-launch.md。禁止在 SQL / Git 中提交可直接使用的固定演示密码。
--
-- 依赖：tenant / app_user / user_tenant_membership / role_permission 表已存在
--       （由 sql/db_tenant.sql 与 sql/db_identity.sql 创建）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 两个演示租户（只支持中文；both 启用；currency=CNY）
-- ---------------------------------------------------------------------
INSERT INTO tenant
  (id, tenant_code, store_name, store_description, industry, country_code, timezone, currency,
   platform, external_store_id, external_store_url,
   owner_name, owner_email, owner_phone, owner_country,
   default_lang, support_langs, auto_reply_enabled,
   welcome_message, signature,
   subscription_plan, monthly_token_budget, monthly_message_quota, qps_limit, concurrent_session_limit,
   status, is_deleted, version)
VALUES
  (1001, 'OM-1001', '星途户外用品店', '专注户外装备的本地演示店铺：背包、冲锋衣、徒步鞋、露营装备等。', '户外运动', 'CN', 'Asia/Shanghai', 'CNY',
   'custom', 'local-1001', 'https://shop.example.com/1001',
   '张海涛', 'owner1001@example.com', '13800000001', 'CN',
   'zh', '["zh"]', 1,
   '您好，这里是星途户外用品店智能客服，请问有什么可以帮您？', '— 星途户外用品店',
   'PRO', 500000, 5000, 20, 200,
   1, 0, 0),
  (1002, 'OM-1002', '智选数码生活馆', '精选数码配件与智能硬件：耳机、键鼠、移动电源、手表等。', '数码科技', 'CN', 'Asia/Shanghai', 'CNY',
   'custom', 'local-1002', 'https://shop.example.com/1002',
   '李文博', 'owner1002@example.com', '13800000002', 'CN',
   'zh', '["zh"]', 1,
   '您好，这里是智选数码生活馆智能客服，请问有什么可以帮您？', '— 智选数码生活馆',
   'PRO', 500000, 5000, 20, 200,
   1, 0, 0)
ON DUPLICATE KEY UPDATE
  store_name = VALUES(store_name),
  store_description = VALUES(store_description),
  industry = VALUES(industry),
  currency = VALUES(currency),
  platform = VALUES(platform),
  external_store_id = VALUES(external_store_id),
  external_store_url = VALUES(external_store_url),
  owner_name = VALUES(owner_name),
  owner_email = VALUES(owner_email),
  owner_phone = VALUES(owner_phone),
  owner_country = VALUES(owner_country),
  default_lang = VALUES(default_lang),
  support_langs = VALUES(support_langs),
  auto_reply_enabled = VALUES(auto_reply_enabled),
  welcome_message = VALUES(welcome_message),
  signature = VALUES(signature),
  subscription_plan = VALUES(subscription_plan),
  monthly_token_budget = VALUES(monthly_token_budget),
  monthly_message_quota = VALUES(monthly_message_quota),
  qps_limit = VALUES(qps_limit),
  concurrent_session_limit = VALUES(concurrent_session_limit),
  status = VALUES(status),
  is_deleted = VALUES(is_deleted);

-- ---------------------------------------------------------------------
-- 2. 角色权限定义（与 sql/db_identity.sql 一致；此处幂等重申，避免漏跑）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO role_permission (role_key, permission_key) VALUES
  ('TENANT_ADMIN', 'tenant:manage'),
  ('TENANT_ADMIN', 'ticket:assign'),
  ('TENANT_ADMIN', 'ticket:resolve'),
  ('TENANT_ADMIN', 'inbox:takeover'),
  ('TENANT_ADMIN', 'inbox:reply'),
  ('TENANT_ADMIN', 'action:approve'),
  ('TENANT_ADMIN', 'knowledge:write'),
  ('TENANT_ADMIN', 'knowledge:review'),
  ('TENANT_ADMIN', 'integration:manage'),
  ('TENANT_ADMIN', 'webhook:replay'),
  ('TENANT_ADMIN', 'audit:read'),
  ('TENANT_ADMIN', 'qa:review'),
  ('TENANT_ADMIN', 'eval:run'),
  ('SUPPORT_SUPERVISOR', 'ticket:assign'),
  ('SUPPORT_SUPERVISOR', 'ticket:resolve'),
  ('SUPPORT_SUPERVISOR', 'inbox:takeover'),
  ('SUPPORT_SUPERVISOR', 'inbox:reply'),
  ('SUPPORT_SUPERVISOR', 'action:approve'),
  ('SUPPORT_SUPERVISOR', 'knowledge:review'),
  ('SUPPORT_SUPERVISOR', 'audit:read'),
  ('SUPPORT_SUPERVISOR', 'qa:review'),
  ('SUPPORT_SUPERVISOR', 'eval:run'),
  ('SUPPORT_AGENT', 'ticket:resolve'),
  ('SUPPORT_AGENT', 'inbox:takeover'),
  ('SUPPORT_AGENT', 'inbox:reply'),
  ('READ_ONLY_AUDITOR', 'audit:read');

-- ---------------------------------------------------------------------
-- 3. 后台演示身份（7 个：平台管理员 1 + 每租户 3 个）
--
-- 全部默认为 DISABLED：password_hash 为不可用占位串，无法登录。
-- 启用方式（任选其一，禁止把真实密码写进本文件）：
--   A) 用平台管理员在后台 /api/admin/users 创建/重置密码；
--   B) 用应用生成 BCrypt 后再 UPDATE（见 docs/demo-zh-launch.md）。
-- 说明：本文件的演示身份 id 采用 9001-9007 固定块，便于重置脚本识别。
-- ---------------------------------------------------------------------
INSERT INTO app_user (id, email, password_hash, display_name, platform_admin, status, token_version)
VALUES
  (9001, 'demo-admin@example.com',   '$2a$12$DEMO.DISABLED.PLACEHOLDER.NOT_A_REAL_HASH', '平台管理员', 1, 'DISABLED', 1),
  (9002, 'demo-1001-admin@example.com',      '$2a$12$DEMO.DISABLED.PLACEHOLDER.NOT_A_REAL_HASH', '星途租户管理员', 0, 'DISABLED', 1),
  (9003, 'demo-1001-supervisor@example.com', '$2a$12$DEMO.DISABLED.PLACEHOLDER.NOT_A_REAL_HASH', '星途客服主管',   0, 'DISABLED', 1),
  (9004, 'demo-1001-agent@example.com',      '$2a$12$DEMO.DISABLED.PLACEHOLDER.NOT_A_REAL_HASH', '星途客服',       0, 'DISABLED', 1),
  (9005, 'demo-1002-admin@example.com',      '$2a$12$DEMO.DISABLED.PLACEHOLDER.NOT_A_REAL_HASH', '智选租户管理员', 0, 'DISABLED', 1),
  (9006, 'demo-1002-supervisor@example.com', '$2a$12$DEMO.DISABLED.PLACEHOLDER.NOT_A_REAL_HASH', '智选客服主管',   0, 'DISABLED', 1),
  (9007, 'demo-1002-agent@example.com',      '$2a$12$DEMO.DISABLED.PLACEHOLDER.NOT_A_REAL_HASH', '智选客服',       0, 'DISABLED', 1)
ON DUPLICATE KEY UPDATE
  display_name = VALUES(display_name),
  platform_admin = VALUES(platform_admin),
  -- 不改动 status / password_hash：避免重置时把已启用账号再次禁用或覆盖密码
  token_version = VALUES(token_version);

-- ---------------------------------------------------------------------
-- 4. 用户与租户成员关系（真实 RBAC 关联；平台管理员不建 membership）
-- ---------------------------------------------------------------------
INSERT INTO user_tenant_membership (user_id, tenant_id, role_key, status)
VALUES
  (9002, 1001, 'TENANT_ADMIN',       'ACTIVE'),
  (9003, 1001, 'SUPPORT_SUPERVISOR', 'ACTIVE'),
  (9004, 1001, 'SUPPORT_AGENT',      'ACTIVE'),
  (9005, 1002, 'TENANT_ADMIN',       'ACTIVE'),
  (9006, 1002, 'SUPPORT_SUPERVISOR', 'ACTIVE'),
  (9007, 1002, 'SUPPORT_AGENT',      'ACTIVE')
ON DUPLICATE KEY UPDATE
  status = VALUES(status);

-- 提示：默认 DISABLED 身份无法登录属预期行为。
-- 如需可登录的演示账号，请按 docs/demo-zh-launch.md 第 4 节通过环境变量 / 后台创建。
