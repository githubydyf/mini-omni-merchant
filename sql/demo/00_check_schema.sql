-- =====================================================================
-- 00_check_schema.sql —— 演示数据工程：只读现状盘点（不做任何修改）
--
-- 用途：执行本重置/初始化前，先确认当前连接的是开发演示库，
--       并盘点演示数据的分布与标记，供人工审核。
-- 纯 SELECT，不含任何写入/删除语句，可安全反复执行。
--
-- 连接示例：
--   mysql -uroot -p123456 -h127.0.0.1 -P3306 mini-omni-merchant < sql/demo/00_check_schema.sql
-- =====================================================================

SELECT '=== 0. 当前数据库 / 连接确认 ===' AS section;
SELECT DATABASE() AS current_database, VERSION() AS mysql_version,
       CURRENT_USER() AS db_user, NOW() AS checked_at;

SELECT '=== 1. 全部业务表与行数 ===' AS section;
SELECT table_name, table_rows, engine, table_comment
FROM information_schema.tables
WHERE table_schema = DATABASE()
ORDER BY table_name;

SELECT '=== 2. 租户清单（演示租户应为 1001 / 1002）===' AS section;
SELECT id, tenant_code, store_name, platform, external_store_id,
       currency, default_lang, status, auto_reply_enabled, is_deleted
FROM tenant ORDER BY id;

SELECT '=== 3. 各租户业务数据分布 ===' AS section;
SELECT 'customer' AS tbl, tenant_id, COUNT(*) AS rows_cnt, MIN(id) AS min_id, MAX(id) AS max_id FROM customer GROUP BY tenant_id
UNION ALL SELECT 'product',      tenant_id, COUNT(*), MIN(id), MAX(id) FROM product GROUP BY tenant_id
UNION ALL SELECT 'order_info',   tenant_id, COUNT(*), MIN(id), MAX(id) FROM order_info GROUP BY tenant_id
UNION ALL SELECT 'conversation', tenant_id, COUNT(*), MIN(id), MAX(id) FROM conversation GROUP BY tenant_id
UNION ALL SELECT 'chat_message', tenant_id, COUNT(*), MIN(id), MAX(id) FROM chat_message GROUP BY tenant_id
UNION ALL SELECT 'escalation_record', tenant_id, COUNT(*), MIN(id), MAX(id) FROM escalation_record GROUP BY tenant_id
UNION ALL SELECT 'ticket',       tenant_id, COUNT(*), MIN(id), MAX(id) FROM ticket GROUP BY tenant_id
UNION ALL SELECT 'return_request', tenant_id, COUNT(*), MIN(id), MAX(id) FROM return_request GROUP BY tenant_id
UNION ALL SELECT 'agent_run',    tenant_id, COUNT(*), MIN(id), MAX(id) FROM agent_run GROUP BY tenant_id
ORDER BY tbl, tenant_id;

SELECT '=== 4. 演示数据标记盘点 ===' AS section;
SELECT 'customer(external_customer_id 前缀)' AS marker, external_customer_id AS sample, tenant_id
FROM customer WHERE external_customer_id LIKE 'cus_%' OR external_customer_id LIKE 'demo%' LIMIT 8;
SELECT 'product(external_product_id 前缀)' AS marker, external_product_id AS sample, tenant_id
FROM product WHERE external_product_id LIKE 'prod_%' OR external_product_id LIKE 'demo%' LIMIT 8;
SELECT 'order(sync_source)' AS marker, sync_source AS sample, COUNT(*) AS n FROM order_info GROUP BY sync_source;
SELECT 'conversation(uuid 前缀统计)' AS marker, 'demo-zh-%' AS prefix_kind, COUNT(*) AS n FROM conversation WHERE conversation_uuid LIKE 'demo-zh-%'
UNION ALL SELECT 'conversation(uuid 前缀统计)', 'conv-%(历史演示)', COUNT(*) FROM conversation WHERE conversation_uuid LIKE 'conv-%'
UNION ALL SELECT 'conversation(uuid 前缀统计)', '随机 UUID(运行期)', COUNT(*) FROM conversation WHERE conversation_uuid NOT LIKE 'conv-%' AND conversation_uuid NOT LIKE 'demo-zh-%';
SELECT 'chat_message(uuid 前缀统计)' AS marker, 'demo-zh-msg-%' AS prefix_kind, COUNT(*) AS n FROM chat_message WHERE message_uuid LIKE 'demo-zh-msg-%'
UNION ALL SELECT 'chat_message(uuid 前缀统计)', 'msg-%(历史演示)', COUNT(*) FROM chat_message WHERE message_uuid LIKE 'msg-%'
UNION ALL SELECT 'chat_message(uuid 前缀统计)', '随机 UUID(运行期)', COUNT(*) FROM chat_message WHERE message_uuid NOT LIKE 'msg-%' AND message_uuid NOT LIKE 'demo-zh-msg-%';
SELECT 'ticket/escalation(ticket_no 前缀)' AS marker, ticket_no AS sample, status FROM ticket
WHERE ticket_no LIKE 'TKT-%' OR ticket_no LIKE 'DEMO-ZH-%' ORDER BY id LIMIT 8;
SELECT 'return_request(request_no 前缀)' AS marker, request_no AS sample, status FROM return_request
WHERE request_no LIKE 'RR-%' OR request_no LIKE 'DEMO-ZH-RR-%' ORDER BY id LIMIT 8;
SELECT 'commerce_action_request(request_no 前缀)' AS marker, request_no AS sample, status FROM commerce_action_request
WHERE request_no LIKE 'AR-%' OR request_no LIKE 'DEMO-ZH-AR-%' ORDER BY id LIMIT 8;

SELECT '=== 5. 身份 / RBAC 现状 ===' AS section;
SELECT id, email, platform_admin, status FROM app_user ORDER BY id;
SELECT user_id, tenant_id, role_key, status FROM user_tenant_membership ORDER BY user_id, tenant_id;
SELECT role_key, COUNT(*) AS permission_cnt FROM role_permission GROUP BY role_key ORDER BY role_key;

SELECT '=== 6. 无法安全识别为演示的数据（运行期残留，需人工确认后才可清理）===' AS section;
SELECT 'conversation(随机UUID)' AS item, COUNT(*) AS n FROM conversation
WHERE conversation_uuid NOT LIKE 'conv-%' AND conversation_uuid NOT LIKE 'demo-zh-%'
UNION ALL SELECT 'chat_message(随机UUID)', COUNT(*) FROM chat_message
WHERE message_uuid NOT LIKE 'msg-%' AND message_uuid NOT LIKE 'demo-zh-msg-%'
UNION ALL SELECT 'agent_run(运行期Trace)', COUNT(*) FROM agent_run
UNION ALL SELECT 'agent_step(运行期Trace)', COUNT(*) FROM agent_step
UNION ALL SELECT 'tool_call_log(运行期)', COUNT(*) FROM tool_call_log
UNION ALL SELECT 'agent_conversation_state', COUNT(*) FROM agent_conversation_state
UNION ALL SELECT 'agent_state_transition', COUNT(*) FROM agent_state_transition
UNION ALL SELECT 'agent_idempotency_guard', COUNT(*) FROM agent_idempotency_guard
UNION ALL SELECT 'app_user(测试账号 it-*)', COUNT(*) FROM app_user WHERE email LIKE 'it-%' OR email LIKE 'widget-it-%'
UNION ALL SELECT 'security_audit_event', COUNT(*) FROM security_audit_event
UNION ALL SELECT 'refresh_token', COUNT(*) FROM refresh_token
UNION ALL SELECT 'revoked_access_token', COUNT(*) FROM revoked_access_token;

SELECT '=== 7. 结论提示 ===' AS section;
SELECT '请确认 current_database 为开发演示库（mini-omni-merchant），且非生产库；第 6 节的数据为运行期残留，默认不会自动清理。' AS reminder;
