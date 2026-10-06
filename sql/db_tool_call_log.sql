-- ============================================================================
-- db_tool_call_log.sql —— 工具调用日志表
--
-- 复现自参考项目 db_main.sql / V1__core_schema.sql 的表 8 tool_call_log。
-- 字段与索引完整保留，不做简化（部分字段当前暂不写入，结构先建好）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `tool_call_log` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT  COMMENT '日志ID',
  `trace_id`          VARCHAR(64)     NOT NULL                  COMMENT '链路追踪ID',
  `span_id`           VARCHAR(64)     DEFAULT NULL              COMMENT 'Span ID(若集成 OpenTelemetry)',
  `tenant_id`         BIGINT UNSIGNED NOT NULL                  COMMENT '租户ID',
  `conversation_uuid` VARCHAR(64)     NOT NULL                  COMMENT '会话UUID',
  `message_uuid`      VARCHAR(64)     DEFAULT NULL              COMMENT '关联的 assistant 消息UUID',

  `tool_call_id`      VARCHAR(64)     NOT NULL                  COMMENT '工具调用ID(本地位)',
  `tool_name`         VARCHAR(64)     NOT NULL                  COMMENT '工具名',
  `tool_version`      VARCHAR(16)     DEFAULT 'v1'              COMMENT '工具版本(支持灰度)',
  `params`            JSON            DEFAULT NULL              COMMENT '入参 JSON',
  `params_hash`       CHAR(32)        DEFAULT NULL              COMMENT '入参MD5(用于缓存命中判断)',

  `success`           TINYINT(1)      NOT NULL DEFAULT 0        COMMENT '是否成功',
  `result`            JSON            DEFAULT NULL              COMMENT '返回结果',
  `result_size_bytes` INT             DEFAULT NULL              COMMENT '返回数据大小(便于发现异常大返回)',
  `error_code`        VARCHAR(64)     DEFAULT NULL              COMMENT '错误码',
  `error_message`     VARCHAR(2048)   DEFAULT NULL              COMMENT '错误信息',
  `error_stack`       TEXT            DEFAULT NULL              COMMENT '异常堆栈(仅 ERROR 级别才存)',

  `started_at`        DATETIME(3)     NOT NULL                  COMMENT '开始时间',
  `ended_at`          DATETIME(3)     DEFAULT NULL              COMMENT '结束时间',
  `latency_ms`        INT             DEFAULT NULL              COMMENT '耗时 ms',
  `cache_hit`         TINYINT(1)      NOT NULL DEFAULT 0        COMMENT '是否命中缓存',

  `retry_count`       INT             NOT NULL DEFAULT 0        COMMENT '重试次数',
  `is_retry`          TINYINT(1)      NOT NULL DEFAULT 0        COMMENT '本次是否是重试',
  `original_call_id`  VARCHAR(64)     DEFAULT NULL              COMMENT '首次调用ID(若是重试)',

  `iteration_index`   TINYINT         DEFAULT NULL              COMMENT 'ReAct 第几轮',
  `triggered_by_model` VARCHAR(64)    DEFAULT NULL              COMMENT '触发本次调用的 LLM 模型',
  `client_ip`         VARCHAR(64)     DEFAULT NULL              COMMENT '客户端 IP',

  `created_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`id`),
  KEY        `idx_trace`           (`trace_id`)                                       COMMENT '链路追踪',
  KEY        `idx_tenant_tool`     (`tenant_id`, `tool_name`, `created_at`)           COMMENT '工具调用统计',
  KEY        `idx_tenant_success`  (`tenant_id`, `success`, `created_at`)             COMMENT '失败率统计',
  KEY        `idx_conversation`    (`conversation_uuid`, `created_at`)                COMMENT '会话工具链',
  KEY        `idx_message`         (`message_uuid`)                                   COMMENT '消息关联工具',
  KEY        `idx_created`         (`created_at`)                                     COMMENT '时间范围扫描'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工具调用日志';
