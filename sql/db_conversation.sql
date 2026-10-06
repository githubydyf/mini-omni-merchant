-- ============================================================================
-- db_conversation.sql —— 基础会话模块（conversation / chat_message）
--
-- 复现自参考项目：
--   D:\code\java_code\agent\spring-ai-crossborder-customer-service\sql\db_main.sql
--   （表 3 conversation、表 4 chat_message）
--
-- 说明：
--   1. DDL 与参考项目逐字段一致，仅加 IF NOT EXISTS 以便幂等执行。
--      当前库 mini-omni-merchant 中这两张表已存在且结构一致，DDL 为留档/可重建用。
--   2. 本阶段不启用多租户 SQL 自动隔离，tenant_id 只是普通字段，仍按参考项目保留。
--   3. 种子数据与现有 customer(2001-2005 / 2011-2015)、
--      order_info(#1001-#1005 / #2001-#2005) 相互关联。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 表 3: conversation (会话表)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `conversation` (
  `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT     COMMENT '会话ID',
  `conversation_uuid`    VARCHAR(64)     NOT NULL                     COMMENT '会话UUID(对外暴露,不暴露自增ID)',
  `tenant_id`            BIGINT UNSIGNED NOT NULL                     COMMENT '租户ID',
  `customer_id`          BIGINT UNSIGNED DEFAULT NULL                 COMMENT '客户ID(关联 customer.id,首次咨询可能为空)',

  `external_customer_id` VARCHAR(128)    DEFAULT NULL                 COMMENT '平台客户ID',
  `customer_email`       VARCHAR(128)    DEFAULT NULL                 COMMENT '客户邮箱(用于身份验证)',
  `customer_name`        VARCHAR(128)    DEFAULT NULL                 COMMENT '客户名(快照)',

  `related_order_id`     VARCHAR(128)    DEFAULT NULL                 COMMENT '关联的平台订单号(可空)',
  `related_product_ids`  JSON            DEFAULT NULL                 COMMENT '咨询涉及的商品ID列表',

  `channel`              VARCHAR(32)     NOT NULL DEFAULT 'WEB'       COMMENT '渠道:WEB/EMAIL/WHATSAPP/MESSENGER/PLATFORM_IM',
  `language`             VARCHAR(8)      DEFAULT NULL                 COMMENT '会话主语言(首条消息检测)',
  `intent_primary`       VARCHAR(32)     DEFAULT NULL                 COMMENT '主意图:ORDER_QUERY/LOGISTICS/REFUND/PRODUCT/COMPLAINT/GREETING/UNCLEAR',
  `intent_history`       JSON            DEFAULT NULL                 COMMENT '历史意图变化轨迹',
  `sentiment`            VARCHAR(16)     DEFAULT NULL                 COMMENT '情感倾向:POSITIVE/NEUTRAL/NEGATIVE/ANGRY',
  `sentiment_score`      DECIMAL(4,3)    DEFAULT NULL                 COMMENT '情感分[-1,1]',

  `status`               TINYINT         NOT NULL DEFAULT 1           COMMENT '1:进行中(AI) 2:已完成 3:已升级人工 4:人工处理中 5:已关闭 6:已超时',
  `escalated`            TINYINT(1)      NOT NULL DEFAULT 0           COMMENT '是否升级人工:0否1是',
  `escalation_reason`    VARCHAR(64)     DEFAULT NULL                 COMMENT '升级原因:LOW_CONFIDENCE/AMOUNT_LIMIT/NEGATIVE_SENTIMENT/MAX_ITER/USER_REQUEST/AI_PROACTIVE',
  `escalated_at`         DATETIME(3)     DEFAULT NULL                 COMMENT '升级时间',
  `human_agent_id`       BIGINT UNSIGNED DEFAULT NULL                 COMMENT '接管的人工客服ID',
  `priority`             TINYINT         NOT NULL DEFAULT 2           COMMENT '优先级:1低 2中 3高 4紧急',

  `message_count`        INT             NOT NULL DEFAULT 0           COMMENT '消息总数(user+assistant)',
  `tool_call_count`      INT             NOT NULL DEFAULT 0           COMMENT '工具调用总数',
  `total_prompt_tokens`  BIGINT          NOT NULL DEFAULT 0           COMMENT '会话累计 prompt token',
  `total_completion_tokens` BIGINT       NOT NULL DEFAULT 0           COMMENT '会话累计 completion token',
  `total_cost_usd`       DECIMAL(12,6)   NOT NULL DEFAULT 0.000000    COMMENT '会话总成本 USD',
  `first_response_ms`    INT             DEFAULT NULL                 COMMENT '首字响应时间 ms',
  `avg_response_ms`      INT             DEFAULT NULL                 COMMENT '平均响应时间 ms',

  `csat_score`           TINYINT         DEFAULT NULL                 COMMENT '客户满意度[1,5]',
  `csat_comment`         VARCHAR(1024)   DEFAULT NULL                 COMMENT '客户评论',
  `csat_submitted_at`    DATETIME        DEFAULT NULL                 COMMENT '评分提交时间',
  `resolved`             TINYINT(1)      DEFAULT NULL                 COMMENT '问题是否解决:NULL未知 0否 1是',

  `started_at`           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '会话开始时间',
  `last_message_at`      DATETIME(3)     DEFAULT NULL                 COMMENT '最后消息时间',
  `ended_at`             DATETIME(3)     DEFAULT NULL                 COMMENT '会话结束时间',
  `duration_seconds`     INT             DEFAULT NULL                 COMMENT '会话时长秒(ended-started)',

  `ext_attr`             JSON            DEFAULT NULL                 COMMENT '扩展属性',
  `tags`                 JSON            DEFAULT NULL                 COMMENT '标签 ["urgent","vip","followup"]',
  `is_deleted`           TINYINT(1)      NOT NULL DEFAULT 0           COMMENT '逻辑删除',
  `version`              INT             NOT NULL DEFAULT 0           COMMENT '乐观锁',
  `created_at`           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_uuid`      (`conversation_uuid`)                                       COMMENT 'UUID 唯一',
  KEY        `idx_tenant_customer`       (`tenant_id`, `customer_id`, `started_at`)                  COMMENT '客户的会话列表',
  KEY        `idx_tenant_status`         (`tenant_id`, `status`, `started_at`)                       COMMENT '按状态筛选',
  KEY        `idx_tenant_started`        (`tenant_id`, `started_at`)                                 COMMENT '时间倒序列表',
  KEY        `idx_tenant_escalated`      (`tenant_id`, `escalated`, `escalated_at`)                  COMMENT '人工工单查询',
  KEY        `idx_tenant_intent`         (`tenant_id`, `intent_primary`)                             COMMENT '按意图统计',
  KEY        `idx_human_agent`           (`human_agent_id`, `status`)                                COMMENT '客服我的工单',
  KEY        `idx_related_order`         (`tenant_id`, `related_order_id`)                           COMMENT '订单关联会话查询',
  KEY        `idx_last_message`          (`last_message_at`)                                         COMMENT '超时会话扫描'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话表';


-- ---------------------------------------------------------------------------
-- 表 4: chat_message (消息表)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_message` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT     COMMENT '消息ID',
  `message_uuid`        VARCHAR(64)     NOT NULL                     COMMENT '消息UUID',
  `conversation_uuid`   VARCHAR(64)     NOT NULL                     COMMENT '会话UUID(冗余便于跨表查询)',
  `conversation_id`     BIGINT UNSIGNED NOT NULL                     COMMENT '会话ID',
  `tenant_id`           BIGINT UNSIGNED NOT NULL                     COMMENT '租户ID',

  `role`                VARCHAR(16)     NOT NULL                     COMMENT '角色:user/assistant/system/tool',
  `seq_no`              INT             NOT NULL                     COMMENT '会话内消息序号(从1开始)',
  `content`             MEDIUMTEXT      NOT NULL                     COMMENT '消息内容(原文,可能含富文本/markdown)',
  `content_type`        VARCHAR(16)     NOT NULL DEFAULT 'TEXT'      COMMENT '内容类型:TEXT/IMAGE/FILE/VOICE/MARKDOWN/HTML',
  `attachments`         JSON            DEFAULT NULL                 COMMENT '附件列表',

  `original_lang`       VARCHAR(8)      DEFAULT NULL                 COMMENT '原始语言',
  `translated_content`  MEDIUMTEXT      DEFAULT NULL                 COMMENT '翻译为英语后的内容(用于 LLM 处理)',
  `translation_lang`    VARCHAR(8)      DEFAULT NULL                 COMMENT '翻译目标语言',
  `is_translated`       TINYINT(1)      NOT NULL DEFAULT 0           COMMENT '是否翻译过',

  `tool_calls`          JSON            DEFAULT NULL                 COMMENT '工具调用列表',
  `tool_call_id`        VARCHAR(64)     DEFAULT NULL                 COMMENT '本消息是哪次工具调用的结果(role=tool 时)',
  `tool_name`           VARCHAR(64)     DEFAULT NULL                 COMMENT '工具名(role=tool 时)',

  `model_provider`      VARCHAR(32)     DEFAULT NULL                 COMMENT 'LLM 提供商:openai/anthropic/deepseek',
  `model_name`          VARCHAR(64)     DEFAULT NULL                 COMMENT '具体模型名',
  `prompt_tokens`       INT             NOT NULL DEFAULT 0           COMMENT '输入 token 数',
  `completion_tokens`   INT             NOT NULL DEFAULT 0           COMMENT '输出 token 数',
  `total_tokens`        INT             NOT NULL DEFAULT 0           COMMENT '总 token 数',
  `cost_usd`            DECIMAL(12,8)   NOT NULL DEFAULT 0.00000000  COMMENT '本条消息成本 USD',
  `latency_ms`          INT             DEFAULT NULL                 COMMENT '生成耗时 ms',
  `ttfb_ms`             INT             DEFAULT NULL                 COMMENT '首字延迟 ms(流式)',
  `finish_reason`       VARCHAR(32)     DEFAULT NULL                 COMMENT '终止原因:stop/length/tool_calls/content_filter',
  `confidence`          DECIMAL(4,3)    DEFAULT NULL                 COMMENT '置信度[0,1]',

  `is_filtered`         TINYINT(1)      NOT NULL DEFAULT 0           COMMENT '是否被过滤(敏感内容):0否1是',
  `filter_reason`       VARCHAR(128)    DEFAULT NULL                 COMMENT '过滤原因:PII/INJECTION/TOXIC/SPAM',
  `is_streamed`         TINYINT(1)      NOT NULL DEFAULT 0           COMMENT '是否流式输出',

  `iteration_index`     TINYINT         DEFAULT NULL                 COMMENT 'ReAct 第几轮迭代(1-5)',
  `parent_message_id`   BIGINT UNSIGNED DEFAULT NULL                 COMMENT '父消息ID',

  `ext_attr`            JSON            DEFAULT NULL                 COMMENT '扩展属性',
  `created_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_uuid`      (`message_uuid`)                                COMMENT '消息UUID唯一',
  UNIQUE KEY `uk_conv_seq`          (`conversation_id`, `seq_no`)                   COMMENT '会话内序号唯一',
  KEY        `idx_conversation`     (`conversation_uuid`, `created_at`)             COMMENT '加载会话历史(最常用)',
  KEY        `idx_tenant_created`   (`tenant_id`, `created_at`)                     COMMENT '租户消息流',
  KEY        `idx_tenant_role`      (`tenant_id`, `role`, `created_at`)             COMMENT '统计 role 分布',
  KEY        `idx_tool_call`        (`tool_call_id`)                                COMMENT '工具调用追踪',
  KEY        `idx_model`            (`model_name`, `created_at`)                    COMMENT '模型用量统计',
  KEY        `idx_filtered`         (`is_filtered`, `created_at`)                   COMMENT '安全审计'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息表';


-- ============================================================================
-- 种子数据（幂等：先清空本模块两张表再写入）
--
-- 关联关系：
--   tenant 1001: customer 2001-2005  ←→ order #1001-#1005
--   tenant 1002: customer 2011-2015  ←→ order #2001-#2005
--
-- 覆盖场景：订单查询 / 物流查询 / 已签收 / 物流异常 / 商品咨询 /
--           退货咨询 / 退款咨询 / 已取消订单 / 已退款订单 / 普通售前咨询
-- ============================================================================

DELETE FROM `chat_message`;
DELETE FROM `conversation`;

INSERT INTO `conversation`
(`id`, `conversation_uuid`, `tenant_id`, `customer_id`, `external_customer_id`, `customer_email`,
 `customer_name`, `related_order_id`, `channel`, `language`, `intent_primary`, `sentiment`,
 `sentiment_score`, `status`, `escalated`, `escalation_reason`, `escalated_at`, `human_agent_id`,
 `priority`, `message_count`, `tool_call_count`, `total_prompt_tokens`, `total_completion_tokens`,
 `total_cost_usd`, `first_response_ms`, `avg_response_ms`, `csat_score`, `csat_comment`,
 `csat_submitted_at`, `resolved`, `started_at`, `last_message_at`, `ended_at`, `duration_seconds`,
 `tags`, `is_deleted`, `version`, `created_at`, `updated_at`)
VALUES
-- 1) 订单查询：Ava Miller 询问 #1001 何时发货
(5001,'conv-f1001-0001',1001,2001,'cus_fashion_001','ava@example.com','Ava Miller','#1001',
 'WEB','en','ORDER_QUERY','NEUTRAL',0.100,2,0,NULL,NULL,NULL,2,4,2,820,310,0.000640,900,1150,5,
 'Response was fast and clear.','2026-09-26 10:06:00',1,
 '2026-09-26 10:00:00','2026-09-26 10:05:00','2026-09-26 10:05:30',330,'["vip"]',0,0,
 '2026-09-26 10:00:00','2026-09-26 10:06:00'),

-- 2) 已签收：Lucia Garcia 确认 #1002 收到
(5002,'conv-f1001-0002',1001,2002,'cus_fashion_002','lucia@example.es','Lucia Garcia','#1002',
 'EMAIL','es','LOGISTICS','POSITIVE',0.800,2,0,NULL,NULL,NULL,1,4,1,760,240,0.000520,1100,1300,5,
 'Gracias, ya lo recibi.','2026-09-26 11:36:00',1,
 '2026-09-26 11:30:00','2026-09-26 11:35:00','2026-09-26 11:35:20',320,'[]',0,0,
 '2026-09-26 11:30:00','2026-09-26 11:36:00'),

-- 3) 普通售前咨询：Noah Kim 咨询商品，未关联订单
(5003,'conv-f1001-0003',1001,2003,'cus_fashion_003','noah@example.com','Noah Kim',NULL,
 'WEB','en','PRODUCT','NEUTRAL',0.150,2,0,NULL,NULL,NULL,1,4,1,700,260,0.000500,850,1050,NULL,NULL,NULL,1,
 '2026-09-26 14:20:00','2026-09-26 14:27:00','2026-09-26 14:27:30',450,'["presale"]',0,0,
 '2026-09-26 14:20:00','2026-09-26 14:27:00'),

-- 4) 退款咨询：Emma Dubois 询问 #1004 退款进度
(5004,'conv-f1001-0004',1001,2004,'cus_fashion_004','emma@example.fr','Emma Dubois','#1004',
 'WEB','fr','REFUND','NEGATIVE',-0.400,2,0,NULL,NULL,NULL,2,4,2,880,340,0.000700,1200,1450,4,
 'Remboursement recu, merci.','2026-09-25 09:16:00',1,
 '2026-09-25 09:10:00','2026-09-25 09:15:00','2026-09-25 09:15:40',340,'[]',0,0,
 '2026-09-25 09:10:00','2026-09-25 09:16:00'),

-- 5) 已取消订单：Blocked Buyer 追问 #1005，被拉黑，会话关闭
(5005,'conv-f1001-0005',1001,2005,'cus_fashion_005','fraud@example.net','Blocked Buyer','#1005',
 'WEB','en','ORDER_QUERY','ANGRY',-0.900,5,1,'NEGATIVE_SENTIMENT','2026-09-24 16:44:00',NULL,3,3,1,
 540,120,0.000300,1400,1600,NULL,NULL,NULL,0,
 '2026-09-24 16:40:00','2026-09-24 16:44:00','2026-09-24 16:45:00',300,'["risk","blocked"]',0,0,
 '2026-09-24 16:40:00','2026-09-24 16:45:00'),

-- 6) 物流查询：Kenji Sato 查 #2001 在途进度，AI 处理中
(5006,'conv-e1002-0001',1002,2011,'cus_electro_001','kenji@example.jp','Kenji Sato','#2001',
 'WHATSAPP','ja','LOGISTICS','NEUTRAL',0.000,1,0,NULL,NULL,NULL,2,3,1,640,200,0.000420,950,1150,NULL,NULL,NULL,NULL,
 '2026-09-27 09:05:00','2026-09-27 09:09:00',NULL,NULL,'["vip"]',0,0,
 '2026-09-27 09:05:00','2026-09-27 09:09:00'),

-- 7) 已签收：Maya Stone 确认 #2002 收到
(5007,'conv-e1002-0002',1002,2012,'cus_electro_002','maya@example.com','Maya Stone','#2002',
 'WEB','en','LOGISTICS','POSITIVE',0.750,2,0,NULL,NULL,NULL,1,4,1,720,250,0.000480,900,1100,5,
 'Arrived safely, thanks!','2026-09-27 10:21:00',1,
 '2026-09-27 10:15:00','2026-09-27 10:20:00','2026-09-27 10:20:20',320,'[]',0,0,
 '2026-09-27 10:15:00','2026-09-27 10:21:00'),

-- 8) 订单查询：Carlos Ramos 查 #2003 处理状态
(5008,'conv-e1002-0003',1002,2013,'cus_electro_003','carlos@example.mx','Carlos Ramos','#2003',
 'WEB','es','ORDER_QUERY','NEUTRAL',0.050,2,0,NULL,NULL,NULL,2,3,2,660,220,0.000460,1000,1250,NULL,NULL,NULL,1,
 '2026-09-27 12:00:00','2026-09-27 12:05:00','2026-09-27 12:05:30',330,'[]',0,0,
 '2026-09-27 12:00:00','2026-09-27 12:05:00'),

-- 9) 物流异常：Jordan Reed 投诉 #2004 派送异常，升级人工
(5009,'conv-e1002-0004',1002,2014,'cus_electro_004','angry@example.com','Jordan Reed','#2004',
 'WEB','en','LOGISTICS','ANGRY',-0.850,3,1,'NEGATIVE_SENTIMENT','2026-09-27 15:50:00',NULL,3,4,3,
 980,380,0.000820,1600,1900,NULL,'Carrier says delivery exception, need human help',NULL,0,
 '2026-09-27 15:45:00','2026-09-27 15:52:00',NULL,NULL,'["urgent"]',0,0,
 '2026-09-27 15:45:00','2026-09-27 15:52:00'),

-- 10) 退货咨询：Li Wei 咨询 #2005 退货流程，人工处理中
(5010,'conv-e1002-0005',1002,2015,'cus_electro_005','li@example.cn','Li Wei','#2005',
 'WEB','zh','REFUND','NEUTRAL',-0.100,4,1,'USER_REQUEST','2026-09-27 17:24:00',NULL,2,4,1,
 760,260,0.000560,1050,1300,NULL,NULL,NULL,NULL,
 '2026-09-27 17:20:00','2026-09-27 17:26:00',NULL,NULL,'["vip"]',0,0,
 '2026-09-27 17:20:00','2026-09-27 17:26:00'),

-- 11) 已退款订单：Emma Dubois 确认 #1004 退款到账
(5011,'conv-f1001-0006',1001,2004,'cus_fashion_004','emma@example.fr','Emma Dubois','#1004',
 'EMAIL','fr','REFUND','POSITIVE',0.600,2,0,NULL,NULL,NULL,2,3,1,600,190,0.000400,1000,1200,5,
 'Remboursement bien recu.','2026-09-23 08:34:00',1,
 '2026-09-23 08:30:00','2026-09-23 08:33:00','2026-09-23 08:33:40',220,'[]',0,0,
 '2026-09-23 08:30:00','2026-09-23 08:34:00'),

-- 12) 商品咨询：Maya Stone 询问商品规格，未关联订单
(5012,'conv-e1002-0006',1002,2012,'cus_electro_002','maya@example.com','Maya Stone',NULL,
 'WEB','en','PRODUCT','NEUTRAL',0.200,2,0,NULL,NULL,NULL,1,3,2,620,210,0.000440,880,1080,NULL,NULL,NULL,1,
 '2026-09-22 13:10:00','2026-09-22 13:15:00','2026-09-22 13:15:30',330,'[]',0,0,
 '2026-09-22 13:10:00','2026-09-22 13:15:00');


INSERT INTO `chat_message`
(`message_uuid`, `conversation_uuid`, `conversation_id`, `tenant_id`, `role`, `seq_no`,
 `content`, `content_type`, `original_lang`, `is_translated`, `tool_name`, `model_provider`,
 `model_name`, `prompt_tokens`, `completion_tokens`, `total_tokens`, `cost_usd`, `latency_ms`,
 `finish_reason`, `confidence`, `is_streamed`, `iteration_index`, `created_at`)
VALUES
-- ===== 5001 订单查询 (Ava, en, #1001) =====
('msg-5001-1',  'conv-f1001-0001',5001,1001,'user',1,
 'Hi, my order #1001 still shows as shipped. When will it arrive?','TEXT','en',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-26 10:00:10'),
('msg-5001-2',  'conv-f1001-0001',5001,1001,'assistant',2,
 'Hello Ava! Let me check order #1001 for you. It is currently in transit with the carrier and the latest tracking shows it has left the origin facility. Estimated delivery is within 3-5 business days.','TEXT','en',0,'queryOrder','openai','gpt-4o-mini',410,150,560,0.000310,950,'stop',0.960,1,1,'2026-09-26 10:01:00'),
('msg-5001-3',  'conv-f1001-0001',5001,1001,'user',3,
 'Great, could you also send me the tracking number?','TEXT','en',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-26 10:03:00'),
('msg-5001-4',  'conv-f1001-0001',5001,1001,'assistant',4,
 'Of course. The tracking number for #1001 is NS1001US. You can use it on the carrier website for live updates. Is there anything else I can help you with?','TEXT','en',0,'queryOrder','openai','gpt-4o-mini',410,160,570,0.000330,1150,'stop',0.950,1,1,'2026-09-26 10:04:30'),

-- ===== 5002 已签收 (Lucia, es, #1002) =====
('msg-5002-1',  'conv-f1001-0002',5002,1001,'user',1,
 'Hola, quiero confirmar si mi pedido #1002 ya fue entregado.','TEXT','es',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-26 11:30:10'),
('msg-5002-2',  'conv-f1001-0002',5002,1001,'assistant',2,
 'Hola Lucia! Reviso el pedido #1002. El estado es "entregado" y fue firmado en tu direccion. La entrega se completo correctamente.','TEXT','es',0,'queryOrder','openai','gpt-4o-mini',390,140,530,0.000300,1100,'stop',0.970,1,1,'2026-09-26 11:31:00'),
('msg-5002-3',  'conv-f1001-0002',5002,1001,'user',3,
 'Perfecto, muchas gracias. El paquete llego en buen estado.','TEXT','es',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-26 11:33:00'),
('msg-5002-4',  'conv-f1001-0002',5002,1001,'assistant',4,
 'Me alegra saberlo! Si necesitas algo mas, aqui estamos. Gracias por tu compra.','TEXT','es',0,NULL,'openai','gpt-4o-mini',370,100,470,0.000220,1300,'stop',0.980,1,1,'2026-09-26 11:34:30'),

-- ===== 5003 普通售前咨询 (Noah, en, 无订单) =====
('msg-5003-1',  'conv-f1001-0003',5003,1001,'user',1,
 'Hello, do you have the noise-cancelling earbuds in stock? I am thinking about buying them.','TEXT','en',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-26 14:20:10'),
('msg-5003-2',  'conv-f1001-0003',5003,1001,'assistant',2,
 'Hi Noah! Yes, the Active Noise-Cancelling Earbuds Pro (VL-EARBUD-PRO) are in stock. They are priced at 129 USD and currently available for immediate shipping.','TEXT','en',0,'searchProductCatalog','openai','gpt-4o-mini',360,130,490,0.000270,850,'stop',0.940,1,1,'2026-09-26 14:21:00'),
('msg-5003-3',  'conv-f1001-0003',5003,1001,'user',3,
 'Nice. What is the battery life like?','TEXT','en',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-26 14:23:00'),
('msg-5003-4',  'conv-f1001-0003',5003,1001,'assistant',4,
 'The earbuds offer up to 30 hours of total playtime with the charging case, and about 8 hours on a single charge. They also support fast charging.','TEXT','en',0,'searchProductCatalog','openai','gpt-4o-mini',340,130,470,0.000230,1050,'stop',0.930,1,1,'2026-09-26 14:24:30'),

-- ===== 5004 退款咨询 (Emma, fr, #1004) =====
('msg-5004-1',  'conv-f1001-0004',5004,1001,'user',1,
 'Bonjour, ou en est le remboursement de ma commande #1004 ?','TEXT','fr',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-25 09:10:10'),
('msg-5004-2',  'conv-f1001-0004',5004,1001,'assistant',2,
 'Bonjour Emma! La commande #1004 est marquee comme remboursee. Le remboursement a ete traite et devrait apparaitre sur votre moyen de paiement initial sous 3 a 5 jours ouvrables.','TEXT','fr',0,'queryOrder','openai','gpt-4o-mini',420,160,580,0.000320,1200,'stop',0.950,1,1,'2026-09-25 09:11:00'),
('msg-5004-3',  'conv-f1001-0004',5004,1001,'user',3,
 'D accord, mais je ne vois encore rien sur mon compte.','TEXT','fr',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-25 09:13:00'),
('msg-5004-4',  'conv-f1001-0004',5004,1001,'assistant',4,
 'Je comprends votre inquietude. Le delai bancaire peut prendre quelques jours supplementaires selon votre banque. Si vous ne voyez rien apres 5 jours ouvrables, revenez vers nous et nous ouvrirons une verification aupres du processeur de paiement.','TEXT','fr',0,'queryOrder','openai','gpt-4o-mini',460,180,640,0.000380,1450,'stop',0.920,1,1,'2026-09-25 09:14:30'),

-- ===== 5005 已取消订单 (Blocked Buyer, en, #1005) =====
('msg-5005-1',  'conv-f1001-0005',5005,1001,'user',1,
 'Why was my order #1005 cancelled? I want it shipped right now!','TEXT','en',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-24 16:40:10'),
('msg-5005-2',  'conv-f1001-0005',5005,1001,'assistant',2,
 'I can see order #1005 has been cancelled. Refunds for cancelled orders are issued to the original payment method automatically.','TEXT','en',0,'queryOrder','openai','gpt-4o-mini',400,120,520,0.000280,1400,'stop',0.880,1,1,'2026-09-24 16:41:00'),
('msg-5005-3',  'conv-f1001-0005',5005,1001,'assistant',3,
 'Your request requires additional review and has been escalated to our support team. We will follow up with you as soon as possible.','TEXT','en',0,NULL,'openai','gpt-4o-mini',140,120,260,0.000020,1600,'stop',0.700,1,2,'2026-09-24 16:43:30'),

-- ===== 5006 物流查询 (Kenji, ja, #2001) AI 处理中 =====
('msg-5006-1',  'conv-e1002-0001',5006,1002,'user',1,
 '注文 #2001 の配送状況を教えてください。','TEXT','ja',1,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-27 09:05:10'),
('msg-5006-2',  'conv-e1002-0001',5006,1002,'assistant',2,
 'Kenji様、ご注文 #2001 を確認しました。現在発送済みで、輸送中です。追跡番号は NS2001JP です。','TEXT','ja',0,'trackLogistics','openai','gpt-4o-mini',400,140,540,0.000300,950,'stop',0.950,1,1,'2026-09-27 09:06:00'),
('msg-5006-3',  'conv-e1002-0001',5006,1002,'user',3,
 '到着予定日はいつですか?','TEXT','ja',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-27 09:08:00'),

-- ===== 5007 已签收 (Maya, en, #2002) =====
('msg-5007-1',  'conv-e1002-0002',5007,1002,'user',1,
 'Hi, has my order #2002 been delivered yet?','TEXT','en',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-27 10:15:10'),
('msg-5007-2',  'conv-e1002-0002',5007,1002,'assistant',2,
 'Hi Maya! Order #2002 shows as delivered. The carrier marked it as signed for at your address today.','TEXT','en',0,'trackLogistics','openai','gpt-4o-mini',380,120,500,0.000280,900,'stop',0.960,1,1,'2026-09-27 10:16:00'),
('msg-5007-3',  'conv-e1002-0002',5007,1002,'user',3,
 'Yes I just got it. Everything looks good, thank you!','TEXT','en',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-27 10:19:00'),
('msg-5007-4',  'conv-e1002-0002',5007,1002,'assistant',4,
 'Wonderful! Enjoy your purchase, Maya. Feel free to reach out anytime if you need anything else.','TEXT','en',0,NULL,'openai','gpt-4o-mini',340,110,450,0.000200,1100,'stop',0.980,1,1,'2026-09-27 10:20:00'),

-- ===== 5008 订单查询 (Carlos, es, #2003, 已支付处理中) =====
('msg-5008-1',  'conv-e1002-0003',5008,1002,'user',1,
 'Hola, ya pague el pedido #2003, cuando lo van a enviar?','TEXT','es',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-27 12:00:10'),
('msg-5008-2',  'conv-e1002-0003',5008,1002,'assistant',2,
 'Hola Carlos! El pedido #2003 figura como pagado y esta en procesamiento. Se enviara en las proximas 24-48 horas y recibiras el numero de seguimiento.','TEXT','es',0,'queryOrder','openai','gpt-4o-mini',400,150,550,0.000310,1000,'stop',0.950,1,1,'2026-09-27 12:01:00'),
('msg-5008-3',  'conv-e1002-0003',5008,1002,'user',3,
 'Perfecto, gracias por la informacion.','TEXT','es',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-27 12:04:00'),

-- ===== 5009 物流异常 (Jordan, en, #2004) 升级人工 =====
('msg-5009-1',  'conv-e1002-0004',5009,1002,'user',1,
 'My order #2004 tracking says there is a delivery exception. This is the second delay and I am very upset.','TEXT','en',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-27 15:45:10'),
('msg-5009-2',  'conv-e1002-0004',5009,1002,'assistant',2,
 'I am sorry for the trouble. Order #2004 currently shows a delivery exception, which usually means the carrier could not complete the attempt. Let me look up the details.','TEXT','en',0,'trackLogistics','openai','gpt-4o-mini',450,170,620,0.000350,1600,'stop',0.900,1,1,'2026-09-27 15:46:00'),
('msg-5009-3',  'conv-e1002-0004',5009,1002,'user',3,
 'This is unacceptable. I want a human to handle this, not a bot.','TEXT','en',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-27 15:49:00'),
('msg-5009-4',  'conv-e1002-0004',5009,1002,'assistant',4,
 'Understood. I have escalated this conversation to a human agent who will review the carrier exception for order #2004 and contact you shortly.','TEXT','en',0,'escalateToHuman','openai','gpt-4o-mini',360,130,490,0.000210,1900,'stop',0.930,1,2,'2026-09-27 15:51:30'),

-- ===== 5010 退货咨询 (Li Wei, zh, #2005) 人工处理中 =====
('msg-5010-1',  'conv-e1002-0005',5010,1002,'user',1,
 '你好,我的订单 #2005 想退货,请问流程是怎样的?','TEXT','zh',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-27 17:20:10'),
('msg-5010-2',  'conv-e1002-0005',5010,1002,'assistant',2,
 '您好!订单 #2005 当前为已退货状态。如需再次申请退货,请在签收后 30 天内提交申请,商品需保持未使用且包装完整。','TEXT','zh',0,'queryOrder','openai','gpt-4o-mini',420,150,570,0.000320,1050,'stop',0.940,1,1,'2026-09-27 17:21:00'),
('msg-5010-3',  'conv-e1002-0005',5010,1002,'user',3,
 '退款会退到哪里?大概多久到账?','TEXT','zh',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-27 17:23:00'),
('msg-5010-4',  'conv-e1002-0005',5010,1002,'assistant',4,
 '退款将原路返回至您的支付账户,通常在退货验收通过后 3-5 个工作日到账。此会话已转交人工客服为您进一步处理。','TEXT','zh',0,'escalateToHuman','openai','gpt-4o-mini',380,130,510,0.000240,1300,'stop',0.920,1,2,'2026-09-27 17:25:00'),

-- ===== 5011 已退款订单 (Emma, fr, #1004) =====
('msg-5011-1',  'conv-f1001-0006',5011,1001,'user',1,
 'Bonjour, je confirme avoir recu le remboursement de la commande #1004. Merci.','TEXT','fr',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-23 08:30:10'),
('msg-5011-2',  'conv-f1001-0006',5011,1001,'assistant',2,
 'Bonjour Emma! Merci pour votre confirmation. Le remboursement de #1004 est bien cloture de notre cote.','TEXT','fr',0,'queryOrder','openai','gpt-4o-mini',380,110,490,0.000250,1000,'stop',0.970,1,1,'2026-09-23 08:31:00'),
('msg-5011-3',  'conv-f1001-0006',5011,1001,'assistant',3,
 'Nous vous remercions de votre patience. N hesitez pas a revenir vers nous si besoin.','TEXT','fr',0,NULL,'openai','gpt-4o-mini',220,80,300,0.000150,1200,'stop',0.960,1,1,'2026-09-23 08:32:30'),

-- ===== 5012 商品咨询 (Maya, en, 无订单) =====
('msg-5012-1',  'conv-e1002-0006',5012,1002,'user',1,
 'Hi, does the 65W GaN travel charger support international voltage? I travel a lot.','TEXT','en',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-22 13:10:10'),
('msg-5012-2',  'conv-e1002-0006',5012,1002,'assistant',2,
 'Hi Maya! Yes, the 65W GaN Travel Charger (VL-GAN-65) supports 100-240V input, so it works in most countries with the right plug adapter. It also supports USB-C PD fast charging.','TEXT','en',0,'searchProductCatalog','openai','gpt-4o-mini',400,150,550,0.000300,880,'stop',0.950,1,1,'2026-09-22 13:11:00'),
('msg-5012-3',  'conv-e1002-0006',5012,1002,'user',3,
 'Great, that is exactly what I needed. Thanks!','TEXT','en',0,NULL,NULL,NULL,0,0,0,0.00000000,NULL,NULL,NULL,0,NULL,'2026-09-22 13:14:00');


-- ============================================================================
-- 补充：对齐原项目 chat_message 的“多语言”列
--
-- 原项目把翻译相关列拆在 sql/db_multilingual.sql（V18 迁移）里追加，
-- 而实体 ChatMessage.java 引用了这些字段。为保证实体可与原项目逐字一致，
-- 这里补齐这 6 列（幂等，重复执行安全）。
-- 注意：translation_event 表属于多语言证据模块，本阶段不创建。
-- ============================================================================
DROP PROCEDURE IF EXISTS `om_ensure_chat_message_i18n_cols`;
DELIMITER //
CREATE PROCEDURE `om_ensure_chat_message_i18n_cols`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'chat_message'
                   AND column_name = 'detection_confidence') THEN
    ALTER TABLE `chat_message`
      ADD COLUMN `detection_confidence` DECIMAL(6,5) DEFAULT NULL AFTER `original_lang`;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'chat_message'
                   AND column_name = 'translation_provider') THEN
    ALTER TABLE `chat_message`
      ADD COLUMN `translation_provider` VARCHAR(32) DEFAULT NULL AFTER `is_translated`,
      ADD COLUMN `translation_model` VARCHAR(64) DEFAULT NULL AFTER `translation_provider`,
      ADD COLUMN `translation_status` VARCHAR(32) DEFAULT NULL AFTER `translation_model`,
      ADD COLUMN `translation_latency_ms` INT DEFAULT NULL AFTER `translation_status`,
      ADD COLUMN `translation_fallback_reason` VARCHAR(128) DEFAULT NULL AFTER `translation_latency_ms`;
  END IF;
END //
DELIMITER ;
CALL `om_ensure_chat_message_i18n_cols`();
DROP PROCEDURE `om_ensure_chat_message_i18n_cols`;
