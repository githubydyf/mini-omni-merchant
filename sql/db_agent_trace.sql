-- ============================================================================
-- db_agent_trace.sql —— Agent 调用过程追踪（agent_run / agent_step）
--
-- 复现自参考项目：
--   sql/db_observability.sql
--   omni-merchant-bootstrap/src/main/resources/db/migration/mysql/V7__agent_observability.sql
--
-- 重要差异（必须保留，不可照抄原项目 V7）：
--   原项目 V7 中 agent_run.id / agent_step.id 定义为 `BIGINT NOT NULL`（不含自增），
--   而 Java 实体使用 @TableId(type = IdType.AUTO)；原项目靠后来的
--   V23__database_generated_ids.sql 才 ALTER 成 AUTO_INCREMENT。
--   本项目未引入 Flyway，采用手动 SQL 管理，因此这里直接建成
--   `BIGINT UNSIGNED NOT NULL AUTO_INCREMENT`，与实体主键策略一致，避免插不进数据。
--
-- 另外：tool_call_log 当前项目已存在（sql/db_tool_call_log.sql），本文件不重复创建。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `agent_run` (
  `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT         COMMENT '运行ID',
  `tenant_id`              BIGINT          NOT NULL                        COMMENT '租户ID',
  `trace_id`               VARCHAR(64)     NOT NULL                        COMMENT '链路追踪ID(每轮请求唯一)',
  `conversation_uuid`      VARCHAR(64)     DEFAULT NULL                    COMMENT '会话UUID',
  `run_type`               VARCHAR(32)     NOT NULL DEFAULT 'CHAT'         COMMENT '运行类型',
  `intent`                 VARCHAR(64)     DEFAULT NULL                    COMMENT '本轮意图',
  `model_provider`         VARCHAR(32)     DEFAULT NULL                    COMMENT '模型提供商',
  `model_name`             VARCHAR(64)     DEFAULT NULL                    COMMENT '模型名',
  `router_decision`        VARCHAR(256)    DEFAULT NULL                    COMMENT '路由决策',
  `input_redacted`         VARCHAR(2048)   DEFAULT NULL                    COMMENT '脱敏后的用户输入',
  `input_hash`             VARCHAR(64)     DEFAULT NULL                    COMMENT '用户输入 SHA-256(用于去重/比对)',
  `final_answer_redacted`  TEXT            DEFAULT NULL                    COMMENT '脱敏后的最终回答',
  `status`                 VARCHAR(32)     NOT NULL DEFAULT 'RUNNING'      COMMENT 'RUNNING/SUCCESS/FAILED',
  `failure_category`       VARCHAR(64)     DEFAULT NULL                    COMMENT '失败分类',
  `failure_reason`         VARCHAR(1024)   DEFAULT NULL                    COMMENT '失败原因(已脱敏、截断)',
  `prompt_tokens`          BIGINT          NOT NULL DEFAULT 0              COMMENT '输入 token(当前未采集,恒为0)',
  `completion_tokens`      BIGINT          NOT NULL DEFAULT 0              COMMENT '输出 token(当前未采集,恒为0)',
  `cost_usd`               DECIMAL(12,6)   NOT NULL DEFAULT 0.000000       COMMENT '成本(当前未采集,恒为0)',
  `first_token_latency_ms` INT             DEFAULT NULL                    COMMENT '首字延迟 ms(真实测量)',
  `total_latency_ms`       INT             DEFAULT NULL                    COMMENT '总耗时 ms(真实测量)',
  `tool_call_count`        INT             NOT NULL DEFAULT 0              COMMENT '工具调用数(来自 tool_call_log 真实统计)',
  `retrieved_doc_count`    INT             NOT NULL DEFAULT 0              COMMENT '检索文档数(当前未采集,恒为0)',
  `citation_count`         INT             NOT NULL DEFAULT 0              COMMENT '引用数(当前未采集,恒为0)',
  `started_at`             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '开始时间',
  `finished_at`            DATETIME(3)     DEFAULT NULL                    COMMENT '结束时间',
  `created_at`             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trace_id`     (`trace_id`),
  KEY `idx_tenant_started`     (`tenant_id`, `started_at`),
  KEY `idx_tenant_status`      (`tenant_id`, `status`, `started_at`),
  KEY `idx_conversation`       (`tenant_id`, `conversation_uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 执行轨迹(每次调用一条)';


CREATE TABLE IF NOT EXISTS `agent_step` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT           COMMENT '步骤ID',
  `tenant_id`        BIGINT          NOT NULL                          COMMENT '租户ID',
  `agent_run_id`     BIGINT UNSIGNED NOT NULL                          COMMENT '关联 agent_run.id',
  `trace_id`         VARCHAR(64)     NOT NULL                          COMMENT '链路追踪ID(关联 agent_run.trace_id)',
  `step_index`       INT             NOT NULL                          COMMENT '同一 Run 内步骤序号(从1开始)',
  `step_type`        VARCHAR(32)     NOT NULL                          COMMENT 'ROUTER/MEMORY/TOOL/FINAL_ANSWER/FAILURE',
  `name`             VARCHAR(128)    NOT NULL                          COMMENT '步骤名称',
  `status`           VARCHAR(32)     NOT NULL DEFAULT 'SUCCESS'        COMMENT 'SUCCESS/FAILED',
  `input_summary`    VARCHAR(2048)   DEFAULT NULL                      COMMENT '输入摘要(已脱敏)',
  `output_summary`   VARCHAR(2048)   DEFAULT NULL                      COMMENT '输出摘要(已脱敏)',
  `tool_call_id`     VARCHAR(64)     DEFAULT NULL                      COMMENT '工具调用ID(TOOL 步骤)',
  `latency_ms`       INT             DEFAULT NULL                      COMMENT '耗时 ms',
  `failure_category` VARCHAR(64)     DEFAULT NULL                      COMMENT '失败分类',
  `failure_reason`   VARCHAR(1024)   DEFAULT NULL                      COMMENT '失败原因(已脱敏)',
  `metadata_json`    TEXT            DEFAULT NULL                      COMMENT '扩展元数据 JSON',
  `started_at`       DATETIME(3)     DEFAULT NULL,
  `ended_at`         DATETIME(3)     DEFAULT NULL,
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`id`),
  KEY `idx_trace_step`  (`trace_id`, `step_index`),
  KEY `idx_run_step`    (`agent_run_id`, `step_index`),
  KEY `idx_tool_call`   (`tool_call_id`),
  KEY `idx_tenant_type` (`tenant_id`, `step_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 轨迹步骤(一次 Run 多条)';
