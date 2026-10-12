-- =====================================================================
-- 05_seed_after_sales.sql —— 演示工单 / 人工升级 / 售后审批
--
-- 关系链：Conversation → EscalationRecord → Ticket（真实关联）
-- 审批：ReturnRequest / CommerceActionRequest（关联真实订单与操作人）
--
-- 幂等：
--   escalation_record 按 uk_ticket_no(ticket_no) UPSERT（固定 id 6001-6006）
--   ticket 按 uk_ticket_no(ticket_no) UPSERT（固定 id 5201-5206）
--   return_request 按 request_no UPSERT；commerce_action_request 按 request_no UPSERT
-- 操作人 humanAgentId / approvedBy 均指向真实存在的演示客服账户。
-- 依赖：01（身份）/ 03（订单）/ 04（会话）已执行。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 人工升级记录（4-6 条，关联已转人工的演示会话）
--    status：1=待处理(OPEN) 2=已分配(ASSIGNED) 4=已解决(RESOLVED)
-- ---------------------------------------------------------------------
INSERT INTO escalation_record
  (id, ticket_no, tenant_id, conversation_uuid, customer_id, escalation_type, escalation_reason, reason_detail,
   involved_amount, currency, summary, customer_intent, priority,
   assigned_agent_id, assigned_at, assignment_strategy,
   sla_response_seconds, sla_resolve_seconds, sla_response_due_at, sla_resolve_due_at,
   sla_response_breached, sla_resolve_breached, status,
   escalated_back_to_ai, created_at, updated_at, version)
VALUES
  (6101, 'DEMO-ZH-TKT-001', 1001, 'demo-zh-conv-1001-05', 2003, 'CUSTOMER_REQUEST', 'CUSTOMER_REQUEST',
   '客户对已退款订单 #1009 的退款进度有疑问，要求人工核对。', 469.0000, 'CNY',
   '客户要求人工核对其订单 #1009 的退款进度。', 'RETURN_REFUND', 1,
   9004, DATE_SUB(NOW(), INTERVAL 5 HOUR), 'MANUAL',
   1800, 86400, DATE_ADD(NOW(), INTERVAL 5 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY),
   0, 0, 1, 0, DATE_SUB(NOW(), INTERVAL 5 HOUR), DATE_SUB(NOW(), INTERVAL 5 HOUR), 0),
  (6102, 'DEMO-ZH-TKT-002', 1001, 'demo-zh-conv-1001-06', 2005, 'QUALITY_ISSUE', 'QUALITY_ISSUE',
   '订单 #1005 的便携露营灯到货即损坏，客户要求人工处理售后。', 159.0000, 'CNY',
   '露营灯到货损坏，客户要求人工售后。', 'COMPLAINT', 2,
   9004, DATE_SUB(NOW(), INTERVAL 4 HOUR), 'MANUAL',
   1800, 86400, DATE_ADD(NOW(), INTERVAL 2 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY),
   0, 0, 2, 0, DATE_SUB(NOW(), INTERVAL 4 HOUR), DATE_SUB(NOW(), INTERVAL 4 HOUR), 0),
  (6103, 'DEMO-ZH-TKT-003', 1002, 'demo-zh-conv-1002-04', 2013, 'CUSTOMER_REQUEST', 'CUSTOMER_REQUEST',
   '客户请求人工确认订单 #2009 的退款处理结果。', 499.0000, 'CNY',
   '客户请求人工确认订单 #2009 退款结果。', 'RETURN_REFUND', 1,
   9007, DATE_SUB(NOW(), INTERVAL 5 HOUR), 'MANUAL',
   1800, 86400, DATE_ADD(NOW(), INTERVAL 5 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY),
   0, 0, 1, 0, DATE_SUB(NOW(), INTERVAL 5 HOUR), DATE_SUB(NOW(), INTERVAL 5 HOUR), 0),
  (6104, 'DEMO-ZH-TKT-004', 1002, 'demo-zh-conv-1002-05', 2015, 'QUALITY_ISSUE', 'QUALITY_ISSUE',
   '订单 #2005 的氮化镓充电器无法充电，客户投诉质量问题。', 129.0000, 'CNY',
   '充电器无法充电，客户投诉质量问题。', 'COMPLAINT', 2,
   9007, DATE_SUB(NOW(), INTERVAL 3 HOUR), 'MANUAL',
   1800, 86400, DATE_ADD(NOW(), INTERVAL 3 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY),
   0, 0, 2, 0, DATE_SUB(NOW(), INTERVAL 3 HOUR), DATE_SUB(NOW(), INTERVAL 3 HOUR), 0),
  (6105, 'DEMO-ZH-TKT-005', 1002, 'demo-zh-conv-1002-06', 2016, 'CUSTOMER_REQUEST', 'CUSTOMER_REQUEST',
   '客户智能手表表带断裂，要求人工处理换新。', 899.0000, 'CNY',
   '智能手表表带断裂，客户要求人工换新。', 'HUMAN_REQUEST', 1,
   9007, DATE_SUB(NOW(), INTERVAL 2 HOUR), 'MANUAL',
   1800, 86400, DATE_ADD(NOW(), INTERVAL 6 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY),
   0, 0, 1, 0, DATE_SUB(NOW(), INTERVAL 2 HOUR), DATE_SUB(NOW(), INTERVAL 2 HOUR), 0)
ON DUPLICATE KEY UPDATE
  tenant_id = VALUES(tenant_id), conversation_uuid = VALUES(conversation_uuid), customer_id = VALUES(customer_id),
  escalation_type = VALUES(escalation_type), escalation_reason = VALUES(escalation_reason),
  reason_detail = VALUES(reason_detail), summary = VALUES(summary), priority = VALUES(priority),
  assigned_agent_id = VALUES(assigned_agent_id), status = VALUES(status);

-- ---------------------------------------------------------------------
-- 2. 工单（由升级记录投影，source_type='ESCALATION'，source_id=升级记录 id）
--    状态与升级记录保持一致；已分配工单 assigned_agent_id 指向真实客服。
-- ---------------------------------------------------------------------
INSERT INTO ticket
  (id, tenant_id, ticket_no, conversation_uuid, source_type, source_id, channel,
   customer_id, customer_email, subject, summary, intent, priority, status,
   assigned_agent_id, assigned_at, sla_response_due_at, sla_resolve_due_at, sla_state, version, created_at, updated_at)
VALUES
  (5201, 1001, 'DEMO-ZH-TKT-001', 'demo-zh-conv-1001-05', 'ESCALATION', 6101, 'WEB_WIDGET',
   2003, 'wangfang@example.com', '退款进度人工核对', '客户要求人工核对订单 #1009 的退款进度。', 'RETURN_REFUND', 1, 'OPEN',
   9004, DATE_SUB(NOW(), INTERVAL 5 HOUR), DATE_ADD(NOW(), INTERVAL 5 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY), 'DUE_SOON', 0, DATE_SUB(NOW(), INTERVAL 5 HOUR), DATE_SUB(NOW(), INTERVAL 5 HOUR)),
  (5202, 1001, 'DEMO-ZH-TKT-002', 'demo-zh-conv-1001-06', 'ESCALATION', 6102, 'WEB_WIDGET',
   2005, 'chenjing@example.com', '露营灯到货损坏售后', '订单 #1005 露营灯到货即损坏，等待人工处理。', 'COMPLAINT', 2, 'ASSIGNED',
   9004, DATE_SUB(NOW(), INTERVAL 4 HOUR), DATE_ADD(NOW(), INTERVAL 2 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY), 'NORMAL', 0, DATE_SUB(NOW(), INTERVAL 4 HOUR), DATE_SUB(NOW(), INTERVAL 4 HOUR)),
  (5203, 1002, 'DEMO-ZH-TKT-003', 'demo-zh-conv-1002-04', 'ESCALATION', 6103, 'WEB_WIDGET',
   2013, 'wumin@example.com', '退款结果人工确认', '客户请求人工确认订单 #2009 退款结果。', 'RETURN_REFUND', 1, 'OPEN',
   9007, DATE_SUB(NOW(), INTERVAL 5 HOUR), DATE_ADD(NOW(), INTERVAL 5 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY), 'DUE_SOON', 0, DATE_SUB(NOW(), INTERVAL 5 HOUR), DATE_SUB(NOW(), INTERVAL 5 HOUR)),
  (5204, 1002, 'DEMO-ZH-TKT-004', 'demo-zh-conv-1002-05', 'ESCALATION', 6104, 'WEB_WIDGET',
   2015, 'fengxia@example.com', '充电器质量问题投诉', '订单 #2005 充电器无法充电，客户投诉。', 'COMPLAINT', 2, 'ASSIGNED',
   9007, DATE_SUB(NOW(), INTERVAL 3 HOUR), DATE_ADD(NOW(), INTERVAL 3 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY), 'NORMAL', 0, DATE_SUB(NOW(), INTERVAL 3 HOUR), DATE_SUB(NOW(), INTERVAL 3 HOUR)),
  (5205, 1002, 'DEMO-ZH-TKT-005', 'demo-zh-conv-1002-06', 'ESCALATION', 6105, 'WEB_WIDGET',
   2016, 'hejun@example.com', '手表表带断裂换新', '客户智能手表表带断裂，要求人工换新。', 'HUMAN_REQUEST', 1, 'OPEN',
   9007, DATE_SUB(NOW(), INTERVAL 2 HOUR), DATE_ADD(NOW(), INTERVAL 6 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY), 'NORMAL', 0, DATE_SUB(NOW(), INTERVAL 2 HOUR), DATE_SUB(NOW(), INTERVAL 2 HOUR))
ON DUPLICATE KEY UPDATE
  tenant_id = VALUES(tenant_id), conversation_uuid = VALUES(conversation_uuid),
  source_type = VALUES(source_type), source_id = VALUES(source_id), channel = VALUES(channel),
  customer_id = VALUES(customer_id), customer_email = VALUES(customer_email),
  subject = VALUES(subject), summary = VALUES(summary), intent = VALUES(intent),
  priority = VALUES(priority), status = VALUES(status), assigned_agent_id = VALUES(assigned_agent_id),
  sla_state = VALUES(sla_state);

-- ---------------------------------------------------------------------
-- 3. 售后申请（ReturnRequest）：关联真实订单号与客户
--    status：1=待审批 2=审核中 3=已批准 4=已拒绝 5=已完成（见 CommerceApprovalService 定案）
-- ---------------------------------------------------------------------
INSERT INTO return_request
  (id, request_no, tenant_id, request_type, external_order_number, customer_email, reason,
   requested_items, amount, currency, priority, status, approval_required_reason,
   resolution, resolution_note, created_at, updated_at)
VALUES
  (7101, 'DEMO-ZH-RR-0001', 1001, 'RETURN', '#1003', 'wangfang@example.com',
   '尺码不合适，申请退货退款。', '[{"sku":"XT-SHOE-42-001","title":"轻便徒步鞋","quantity":1}]', 469.0000, 'CNY', 1, 1,
   '退货涉及退款，需人工审批', NULL, NULL, DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY)),
  (7102, 'DEMO-ZH-RR-0002', 1001, 'REFUND', '#1009', 'wangfang@example.com',
   '订单已签收但商品不符合预期，申请退款。', '[{"sku":"XT-SHOE-42-001","title":"轻便徒步鞋","quantity":1}]', 469.0000, 'CNY', 1, 3,
   '退款金额达到审批阈值', 'APPROVED_MANUAL', '人工审核通过，走原路退款（仅内部审批，未改写外部平台）。', DATE_SUB(NOW(), INTERVAL 15 DAY), DATE_SUB(NOW(), INTERVAL 14 DAY)),
  (7103, 'DEMO-ZH-RR-0003', 1001, 'REPLACEMENT', '#1005', 'chenjing@example.com',
   '露营灯到货损坏，申请补发。', '[{"sku":"XT-LAMP-001","title":"便携露营灯","quantity":1}]', 159.0000, 'CNY', 2, 4,
   '质量问题补发需人工核实', 'REJECTED', '经核实为运输损坏，建议走退货退款而非补发。', DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 4 DAY)),
  (7104, 'DEMO-ZH-RR-0004', 1001, 'ADDRESS_CHANGE', '#1002', 'lina@example.com',
   '下单后地址填错，申请修改收货地址。', NULL, NULL, 'CNY', 2, 1,
   '地址修改需人工确认', NULL, NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
  (7105, 'DEMO-ZH-RR-0005', 1002, 'RETURN', '#2009', 'wumin@example.com',
   '耳机主动降噪效果不符预期，申请退货。', '[{"sku":"ZX-EAR-ANC-001","title":"主动降噪蓝牙耳机","quantity":1}]', 499.0000, 'CNY', 1, 3,
   '退款金额达到审批阈值', 'APPROVED_MANUAL', '人工审核通过，走原路退款（仅内部审批）。', DATE_SUB(NOW(), INTERVAL 16 DAY), DATE_SUB(NOW(), INTERVAL 15 DAY)),
  (7106, 'DEMO-ZH-RR-0006', 1002, 'REPLACEMENT', '#2005', 'fengxia@example.com',
   '充电器无法充电，申请补发。', '[{"sku":"ZX-GAN-65W-001","title":"65W 氮化镓充电器","quantity":1}]', 129.0000, 'CNY', 2, 1,
   '质量问题补发需人工核实', NULL, NULL, DATE_SUB(NOW(), INTERVAL 3 HOUR), DATE_SUB(NOW(), INTERVAL 3 HOUR)),
  (7107, 'DEMO-ZH-RR-0007', 1002, 'REFUND', '#2001', 'sunyue@example.com',
   '耳机下单后不想要了，申请取消并退款。', '[{"sku":"ZX-EAR-ANC-001","title":"主动降噪蓝牙耳机","quantity":1}]', 499.0000, 'CNY', 1, 2,
   '退款需人工审批', NULL, NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
  (7108, 'DEMO-ZH-RR-0008', 1002, 'CANCEL_ORDER', '#2008', 'zhoutao@example.com',
   '订单已取消，确认无需发货。', '[{"sku":"ZX-HUB-8IN1-001","title":"USB-C 扩展坞 8 合 1","quantity":1}]', 229.0000, 'CNY', 2, 4,
   '取消订单需人工确认', 'REJECTED', '订单已由客户自助取消，无需重复处理。', DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 7 DAY))
ON DUPLICATE KEY UPDATE
  tenant_id = VALUES(tenant_id), request_type = VALUES(request_type),
  external_order_number = VALUES(external_order_number), customer_email = VALUES(customer_email),
  reason = VALUES(reason), requested_items = VALUES(requested_items), amount = VALUES(amount),
  currency = VALUES(currency), priority = VALUES(priority), status = VALUES(status),
  approval_required_reason = VALUES(approval_required_reason),
  resolution = VALUES(resolution), resolution_note = VALUES(resolution_note);

-- ---------------------------------------------------------------------
-- 4. 动作审批请求（CommerceActionRequest）：关联真实订单与操作人
--    status：PENDING_APPROVAL / APPROVED_MANUAL / REJECTED
--    approved_by 指向真实演示用户（仅内部审批，external_write_enabled 恒为 0）。
-- ---------------------------------------------------------------------
INSERT INTO commerce_action_request
  (id, tenant_id, request_no, action_type, platform, external_order_id, external_order_number,
   customer_email, requested_payload, status, risk_reason, approved_by, approved_at, external_result,
   created_at, updated_at)
VALUES
  (8101, 1001, 'DEMO-ZH-AR-0001', 'CANCEL_ORDER', 'custom', 'ord_outdoor_0107', '#1007',
   'zhangwei@example.com', '{"action":"CANCEL_ORDER","reason":"客户申请取消订单"}', 'PENDING_APPROVAL',
   '取消订单需人工确认，避免误取消', NULL, NULL, NULL,
   DATE_SUB(NOW(), INTERVAL 4 HOUR), DATE_SUB(NOW(), INTERVAL 4 HOUR)),
  (8102, 1001, 'DEMO-ZH-AR-0002', 'REFUND', 'custom', 'ord_outdoor_0109', '#1009',
   'wangfang@example.com', '{"action":"REFUND","amount":469.00,"reason":"退款申请人工审批"}', 'APPROVED_MANUAL',
   '退款金额达到审批阈值', 9003, DATE_SUB(NOW(), INTERVAL 14 DAY),
   '人工审批通过；仅记录内部审批结果，未执行外部平台写操作。',
   DATE_SUB(NOW(), INTERVAL 15 DAY), DATE_SUB(NOW(), INTERVAL 14 DAY)),
  (8103, 1002, 'DEMO-ZH-AR-0003', 'REPLACEMENT', 'custom', 'ord_digital_0205', '#2005',
   'fengxia@example.com', '{"action":"REPLACEMENT","reason":"充电器质量问题申请补发"}', 'PENDING_APPROVAL',
   '补发涉及库存与成本，需人工审批', NULL, NULL, NULL,
   DATE_SUB(NOW(), INTERVAL 3 HOUR), DATE_SUB(NOW(), INTERVAL 3 HOUR)),
  (8104, 1002, 'DEMO-ZH-AR-0004', 'CANCEL_ORDER', 'custom', 'ord_digital_0208', '#2008',
   'zhoutao@example.com', '{"action":"CANCEL_ORDER","reason":"客户申请取消订单"}', 'REJECTED',
   '订单已取消，无需重复处理', NULL, NULL, '订单已由客户自助取消。',
   DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 7 DAY))
ON DUPLICATE KEY UPDATE
  tenant_id = VALUES(tenant_id), action_type = VALUES(action_type), platform = VALUES(platform),
  external_order_id = VALUES(external_order_id), external_order_number = VALUES(external_order_number),
  customer_email = VALUES(customer_email), requested_payload = VALUES(requested_payload),
  status = VALUES(status), risk_reason = VALUES(risk_reason), approved_by = VALUES(approved_by),
  external_result = VALUES(external_result);
