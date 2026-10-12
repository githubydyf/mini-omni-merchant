-- =====================================================================
-- 07_wipe_all_business_data.sql —— 【破坏性】清空全部业务数据与运行期数据
--
-- ⚠ 警告：本脚本会删除所有租户的业务数据与 Agent 运行期数据。
--   仅供**开发演示库**使用，执行前必须先备份（scripts/reset-demo-data.ps1
--   或手动 mysqldump）。生产库禁止执行。
--
-- 保留（不清空）：
--   tenant               —— 租户定义（由 01 种子更新为中文）
--   role_permission      —— 角色权限定义（由 01 种子幂等重申）
--   commerce_action_policy —— 审批策略配置
--   app_user / user_tenant_membership —— 保留可登录账号（见文末说明）
--
-- 清空范围（FK 安全：子表 → 父表）：
--   业务：chat_message, conversation, escalation_record, ticket,
--         return_request, commerce_action_request, order_info, product, customer
--   运行期：agent_step, agent_run, agent_state_transition,
--           agent_conversation_state, tool_call_log, agent_idempotency_guard
--   认证运行期：refresh_token, revoked_access_token, security_audit_event
--   演示身份：app_user / user_tenant_membership 中 id 9001-9007（由 01 重新写入）
-- =====================================================================

-- ---------- 业务数据（子表 → 父表） ----------
DELETE FROM chat_message;
DELETE FROM conversation;
DELETE FROM ticket;
DELETE FROM escalation_record;
DELETE FROM return_request;
DELETE FROM commerce_action_request;
DELETE FROM order_info;
DELETE FROM product;
DELETE FROM customer;

-- ---------- Agent 运行期数据 ----------
DELETE FROM agent_step;
DELETE FROM agent_run;
DELETE FROM agent_state_transition;
DELETE FROM agent_conversation_state;
DELETE FROM tool_call_log;
DELETE FROM agent_idempotency_guard;

-- ---------- 认证运行期数据（保留 app_user 主体） ----------
DELETE FROM refresh_token;
DELETE FROM revoked_access_token;
DELETE FROM security_audit_event;

-- ---------- 演示身份（9001-9007）：先清 membership（FK），再清 app_user ----------
DELETE FROM user_tenant_membership WHERE user_id BETWEEN 9001 AND 9007;
DELETE FROM refresh_token WHERE user_id BETWEEN 9001 AND 9007;
DELETE FROM app_user WHERE id BETWEEN 9001 AND 9007;

-- 说明：app_user 中非演示 id（例如平台管理员 id=1）与其它测试登录账号被保留，
--       以保证重置后系统仍可登录。如需一并清理，请人工确认后单独执行。
