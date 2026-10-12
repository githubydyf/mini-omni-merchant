-- =====================================================================
-- 04_seed_conversations.sql —— 演示会话与消息（全中文）
--
-- 会话固定 id 块 5601-5616 + 标记 demo-zh-conv-* / demo-zh-msg-*；
-- 消息为**明确标记的静态演示样例**，不代表真实 DeepSeek 调用结果。
-- 转人工会话 status=3（已升级人工），AI 不再自动回复。
-- 幂等：conversation 按 uk_conversation_uuid、chat_message 按 uk_message_uuid UPSERT。
-- 依赖：02/03 已执行。
-- =====================================================================

INSERT INTO conversation
  (id, conversation_uuid, tenant_id, customer_id, external_customer_id, customer_email, customer_name, related_order_id, channel, language, intent_primary, sentiment, status, escalated, escalation_reason, escalated_at, human_agent_id, priority, message_count, tool_call_count, total_prompt_tokens, total_completion_tokens, total_cost_usd, started_at, last_message_at, is_deleted, version)
VALUES
(5601, 'demo-zh-conv-1001-01', 1001, 2001, 'cus_outdoor_001', 'zhangwei@example.com', '张伟', '#1001', 'WEB_WIDGET', 'zh', 'ORDER_STATUS', 'NEUTRAL', 2, 0, NULL, NULL, NULL, 2, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), 0, 0),
(5602, 'demo-zh-conv-1001-02', 1001, 2002, 'cus_outdoor_002', 'lina@example.com', '李娜', '#1002', 'WEB_WIDGET', 'zh', 'LOGISTICS', 'NEUTRAL', 2, 0, NULL, NULL, NULL, 2, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0, 0),
(5603, 'demo-zh-conv-1001-03', 1001, NULL, NULL, NULL, NULL, NULL, 'WEB_WIDGET', 'zh', 'PRODUCT_ADVICE', 'NEUTRAL', 1, 0, NULL, NULL, NULL, 2, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 6 HOUR), DATE_SUB(NOW(), INTERVAL 6 HOUR), 0, 0),
(5604, 'demo-zh-conv-1001-04', 1001, 2004, 'cus_outdoor_004', 'liuyang@example.com', '刘洋', '#1004', 'WEB_WIDGET', 'zh', 'POLICY_QA', 'NEUTRAL', 2, 0, NULL, NULL, NULL, 2, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 8 HOUR), DATE_SUB(NOW(), INTERVAL 8 HOUR), 0, 0),
(5605, 'demo-zh-conv-1001-05', 1001, 2003, 'cus_outdoor_003', 'wangfang@example.com', '王芳', '#1009', 'WEB_WIDGET', 'zh', 'RETURN_REFUND', 'NEGATIVE', 3, 1, 'CUSTOMER_REQUEST', DATE_SUB(NOW(), INTERVAL 5 HOUR), 9004, 2, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 7 HOUR), DATE_SUB(NOW(), INTERVAL 7 HOUR), 0, 0),
(5606, 'demo-zh-conv-1001-06', 1001, 2005, 'cus_outdoor_005', 'chenjing@example.com', '陈静', '#1005', 'WEB_WIDGET', 'zh', 'HUMAN_REQUEST', 'NEGATIVE', 3, 1, 'QUALITY_ISSUE', DATE_SUB(NOW(), INTERVAL 4 HOUR), 9004, 2, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 6 HOUR), DATE_SUB(NOW(), INTERVAL 6 HOUR), 0, 0),
(5611, 'demo-zh-conv-1002-01', 1002, 2011, 'cus_digital_001', 'sunyue@example.com', '孙悦', '#2001', 'WEB_WIDGET', 'zh', 'ORDER_STATUS', 'NEUTRAL', 2, 0, NULL, NULL, NULL, 2, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), 0, 0),
(5612, 'demo-zh-conv-1002-02', 1002, NULL, NULL, NULL, NULL, NULL, 'WEB_WIDGET', 'zh', 'PRODUCT_ADVICE', 'NEUTRAL', 1, 0, NULL, NULL, NULL, 2, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 7 HOUR), DATE_SUB(NOW(), INTERVAL 7 HOUR), 0, 0),
(5613, 'demo-zh-conv-1002-03', 1002, 2014, 'cus_digital_004', 'zhengkai@example.com', '郑凯', '#2004', 'WEB', 'zh', 'POLICY_QA', 'NEUTRAL', 2, 0, NULL, NULL, NULL, 2, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 9 HOUR), DATE_SUB(NOW(), INTERVAL 9 HOUR), 0, 0),
(5614, 'demo-zh-conv-1002-04', 1002, 2013, 'cus_digital_003', 'wumin@example.com', '吴敏', '#2009', 'WEB_WIDGET', 'zh', 'RETURN_REFUND', 'NEGATIVE', 3, 1, 'CUSTOMER_REQUEST', DATE_SUB(NOW(), INTERVAL 5 HOUR), 9007, 2, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 8 HOUR), DATE_SUB(NOW(), INTERVAL 8 HOUR), 0, 0),
(5615, 'demo-zh-conv-1002-05', 1002, 2015, 'cus_digital_005', 'fengxia@example.com', '冯霞', '#2005', 'WEB_WIDGET', 'zh', 'COMPLAINT', 'NEGATIVE', 3, 1, 'QUALITY_ISSUE', DATE_SUB(NOW(), INTERVAL 3 HOUR), 9007, 2, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 6 HOUR), DATE_SUB(NOW(), INTERVAL 6 HOUR), 0, 0),
(5616, 'demo-zh-conv-1002-06', 1002, 2016, 'cus_digital_006', 'hejun@example.com', '何军', '#2006', 'WEB_WIDGET', 'zh', 'HUMAN_REQUEST', 'NEUTRAL', 3, 1, 'CUSTOMER_REQUEST', DATE_SUB(NOW(), INTERVAL 2 HOUR), 9007, 2, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 4 HOUR), DATE_SUB(NOW(), INTERVAL 4 HOUR), 0, 0)
ON DUPLICATE KEY UPDATE
  customer_id = VALUES(customer_id), external_customer_id = VALUES(external_customer_id),
  customer_email = VALUES(customer_email), customer_name = VALUES(customer_name),
  related_order_id = VALUES(related_order_id), channel = VALUES(channel), language = VALUES(language),
  intent_primary = VALUES(intent_primary), sentiment = VALUES(sentiment), status = VALUES(status),
  escalated = VALUES(escalated), escalation_reason = VALUES(escalation_reason),
  human_agent_id = VALUES(human_agent_id), priority = VALUES(priority), is_deleted = VALUES(is_deleted);

INSERT INTO chat_message
  (message_uuid, conversation_uuid, conversation_id, tenant_id, role, seq_no, content, content_type, original_lang, is_translated, translation_status, prompt_tokens, completion_tokens, total_tokens, cost_usd, is_filtered, is_streamed, created_at)
VALUES
('demo-zh-msg-5601-1', 'demo-zh-conv-1001-01', 5601, 1001, 'user', 1, '我的订单 #1001 发货了吗？', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 2 DAY)),
('demo-zh-msg-5601-2', 'demo-zh-conv-1001-01', 5601, 1001, 'assistant', 2, '您好，已为您查询到订单 #1001 当前状态为「已付款、待发货」，暂未发货。发货后我们会第一时间同步物流信息。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 2 DAY) + INTERVAL 20 SECOND),
('demo-zh-msg-5601-3', 'demo-zh-conv-1001-01', 5601, 1001, 'user', 3, '那大概什么时候能发货？', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 2 DAY) + INTERVAL 60 SECOND),
('demo-zh-msg-5602-1', 'demo-zh-conv-1001-02', 5602, 1001, 'user', 1, '订单 #1002 的物流到哪了？', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 1 DAY)),
('demo-zh-msg-5602-2', 'demo-zh-conv-1001-02', 5602, 1001, 'assistant', 2, '您的订单 #1002 已由顺丰速运发出，物流单号 SF1000000001，当前状态为「运输中」，预计 1 天内送达。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 1 DAY) + INTERVAL 25 SECOND),
('demo-zh-msg-5603-1', 'demo-zh-conv-1001-03', 5603, 1001, 'user', 1, '想买一个徒步用的背包，有推荐吗？', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 6 HOUR)),
('demo-zh-msg-5603-2', 'demo-zh-conv-1001-03', 5603, 1001, 'assistant', 2, '推荐我们的「防水旅行背包 28L」，防泼水面料、多隔层收纳，售价 329 元，适合徒步和短途旅行，目前现货充足。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 6 HOUR) + INTERVAL 18 SECOND),
('demo-zh-msg-5604-1', 'demo-zh-conv-1001-04', 5604, 1001, 'user', 1, '保温杯可以七天无理由退货吗？', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 8 HOUR)),
('demo-zh-msg-5604-2', 'demo-zh-conv-1001-04', 5604, 1001, 'assistant', 2, '根据本店退货政策：自签收之日起 7 天内可申请无理由退货，但需商品完好、不影响二次销售；已拆封使用的个人用品类商品可能不支持无理由退货。具体以政策知识库为准。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 8 HOUR) + INTERVAL 22 SECOND),
('demo-zh-msg-5605-1', 'demo-zh-conv-1001-05', 5605, 1001, 'user', 1, '订单 #1009 我要申请退款。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 7 HOUR)),
('demo-zh-msg-5605-2', 'demo-zh-conv-1001-05', 5605, 1001, 'assistant', 2, '已收到您的退款诉求。退款需要核对下单身份并由人工审核，我已为您提交申请，请提供下单时使用的邮箱以便核验。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 7 HOUR) + INTERVAL 20 SECOND),
('demo-zh-msg-5605-3', 'demo-zh-conv-1001-05', 5605, 1001, 'user', 3, '邮箱是 wangfang@example.com，麻烦尽快处理。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 6 HOUR)),
('demo-zh-msg-5605-4', 'demo-zh-conv-1001-05', 5605, 1001, 'assistant', 4, '好的，您的退款申请已提交人工审核，当前状态为「待人工审批」。审核通过后我们会在原支付渠道退款，请耐心等待，感谢理解。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 5 HOUR)),
('demo-zh-msg-5606-1', 'demo-zh-conv-1001-06', 5606, 1001, 'user', 1, '订单 #1005 买的露营灯收到就是坏的，我要转人工客服。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 6 HOUR)),
('demo-zh-msg-5606-2', 'demo-zh-conv-1001-06', 5606, 1001, 'assistant', 2, '非常抱歉给您带来不便。该问题涉及质量问题售后，我已为您转接人工客服，稍后会有专人为您跟进处理。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 6 HOUR) + INTERVAL 22 SECOND),
('demo-zh-msg-5606-3', 'demo-zh-conv-1001-06', 5606, 1001, 'assistant', 3, '当前咨询已转交人工客服，请稍候。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 4 HOUR)),
('demo-zh-msg-5611-1', 'demo-zh-conv-1002-01', 5611, 1002, 'user', 1, '订单 #2001 怎么还没发货？', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 2 DAY)),
('demo-zh-msg-5611-2', 'demo-zh-conv-1002-01', 5611, 1002, 'assistant', 2, '您好，订单 #2001（主动降噪蓝牙耳机）当前状态为「已付款、待发货」，我们正在安排出库，发货后会同步物流单号。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 2 DAY) + INTERVAL 21 SECOND),
('demo-zh-msg-5612-1', 'demo-zh-conv-1002-02', 5612, 1002, 'user', 1, '有没有适合办公的机械键盘推荐？', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 7 HOUR)),
('demo-zh-msg-5612-2', 'demo-zh-conv-1002-02', 5612, 1002, 'assistant', 2, '推荐「机械键盘 87 键」，热插拔轴体、RGB 背光，支持有线/无线双模，售价 399 元，很适合办公与游戏，目前现货。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 7 HOUR) + INTERVAL 19 SECOND),
('demo-zh-msg-5613-1', 'demo-zh-conv-1002-03', 5613, 1002, 'user', 1, '退货的运费谁承担？', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 9 HOUR)),
('demo-zh-msg-5613-2', 'demo-zh-conv-1002-03', 5613, 1002, 'assistant', 2, '根据本店退货政策：因商品质量问题产生的退货运费由本店承担；七天无理由退货的退货运费一般由买家承担。具体以政策知识库为准。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 9 HOUR) + INTERVAL 23 SECOND),
('demo-zh-msg-5614-1', 'demo-zh-conv-1002-04', 5614, 1002, 'user', 1, '订单 #2009 已退款了吗？我要确认退款进度。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 8 HOUR)),
('demo-zh-msg-5614-2', 'demo-zh-conv-1002-04', 5614, 1002, 'assistant', 2, '您好，订单 #2009 的退款申请需要人工核验，我已为您转接人工客服跟进，请提供下单邮箱以便核实。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 8 HOUR) + INTERVAL 20 SECOND),
('demo-zh-msg-5614-3', 'demo-zh-conv-1002-04', 5614, 1002, 'user', 3, 'wumin@example.com。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 6 HOUR)),
('demo-zh-msg-5614-4', 'demo-zh-conv-1002-04', 5614, 1002, 'assistant', 4, '已记录，人工客服会尽快为您核实并处理，请留意后续回复。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 5 HOUR)),
('demo-zh-msg-5615-1', 'demo-zh-conv-1002-05', 5615, 1002, 'user', 1, '订单 #2005 的充电器用了两天就充不进电了，怎么处理？', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 6 HOUR)),
('demo-zh-msg-5615-2', 'demo-zh-conv-1002-05', 5615, 1002, 'assistant', 2, '非常抱歉。充电器无法充电属于质量问题，我已为您创建售后跟进并转接人工客服核实，请提供订单号与联系方式。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 6 HOUR) + INTERVAL 21 SECOND),
('demo-zh-msg-5616-1', 'demo-zh-conv-1002-06', 5616, 1002, 'user', 1, '我要人工客服，手表表带断了。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 0, DATE_SUB(NOW(), INTERVAL 4 HOUR)),
('demo-zh-msg-5616-2', 'demo-zh-conv-1002-06', 5616, 1002, 'assistant', 2, '已为您转接人工客服，稍后会有专人协助您处理手表表带问题，感谢您的耐心等待。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 4 HOUR) + INTERVAL 18 SECOND),
('demo-zh-msg-5616-3', 'demo-zh-conv-1002-06', 5616, 1002, 'assistant', 3, '当前咨询已转交人工客服，请稍候。', 'TEXT', 'zh', 0, 'NOT_REQUIRED', 0, 0, 0, 0, 0, 1, DATE_SUB(NOW(), INTERVAL 2 HOUR))
ON DUPLICATE KEY UPDATE
  conversation_uuid = VALUES(conversation_uuid), conversation_id = VALUES(conversation_id),
  tenant_id = VALUES(tenant_id), role = VALUES(role), seq_no = VALUES(seq_no), content = VALUES(content),
  content_type = VALUES(content_type), original_lang = VALUES(original_lang);

-- 回填 message_count 与 last_message_at（以真实消息为准）
UPDATE conversation v
SET v.message_count = (
        SELECT COUNT(*) FROM chat_message m
        WHERE m.conversation_uuid = v.conversation_uuid AND m.tenant_id = v.tenant_id),
    v.last_message_at = (
        SELECT MAX(m.created_at) FROM chat_message m
        WHERE m.conversation_uuid = v.conversation_uuid AND m.tenant_id = v.tenant_id)
WHERE v.conversation_uuid LIKE 'demo-zh-conv-%';
