-- OmniMerchant 租户/店铺表。
-- 复用原项目 sql/db_main.sql 中 tenant 表的真实结构；本阶段只启用当前业务需要的列
-- （其余 Shopify 凭证、多语言、订阅等列按原 schema 保留，但不提前开发对应能力）。
-- 现有业务数据中的 tenant_id = 1001 / 1002 必须与之匹配，故此处保留这两条真实租户记录。
-- 本脚本幂等，可重复执行。

CREATE TABLE IF NOT EXISTS `tenant` (
  `id`                       BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT       COMMENT '租户ID(主键)',
  `tenant_code`              VARCHAR(64)      NOT NULL                       COMMENT '租户业务编码(对外展示,如 OM-A1B2C3),全局唯一',

  `store_name`               VARCHAR(128)     NOT NULL                       COMMENT '店铺名称(用户可见)',
  `store_logo_url`           VARCHAR(512)     DEFAULT NULL                   COMMENT '店铺 Logo URL',
  `store_description`        VARCHAR(1024)    DEFAULT NULL                   COMMENT '店铺简介',
  `industry`                 VARCHAR(64)      DEFAULT NULL                   COMMENT '行业类目',
  `country_code`             VARCHAR(8)       DEFAULT NULL                   COMMENT '店铺归属国家 ISO-3166',
  `timezone`                 VARCHAR(64)      DEFAULT 'UTC'                  COMMENT '店铺时区',
  `currency`                 VARCHAR(8)       DEFAULT 'USD'                  COMMENT '默认结算币种 ISO-4217',

  `platform`                 VARCHAR(32)      NOT NULL                       COMMENT '平台:shopify/amazon/woocommerce/tiktok_shop/... /custom',
  `external_store_id`        VARCHAR(128)     NOT NULL                       COMMENT '平台店铺ID',
  `external_store_url`       VARCHAR(512)     DEFAULT NULL                   COMMENT '店铺前台 URL',
  `webhook_secret`           VARCHAR(256)     DEFAULT NULL                   COMMENT 'Webhook HMAC 签名密钥(加密存储)',
  `api_credentials_encrypted` TEXT            DEFAULT NULL                   COMMENT '平台 API 凭证(AES-256 加密)',
  `api_credentials_updated_at` DATETIME       DEFAULT NULL                   COMMENT '凭证最后更新时间',

  `owner_name`               VARCHAR(128)     DEFAULT NULL                   COMMENT '店主姓名',
  `owner_email`              VARCHAR(128)     NOT NULL                       COMMENT '店主邮箱',
  `owner_phone`              VARCHAR(32)      DEFAULT NULL                   COMMENT '店主手机',
  `owner_country`            VARCHAR(8)       DEFAULT NULL                   COMMENT '店主国籍',

  `default_lang`             VARCHAR(8)       NOT NULL DEFAULT 'en'          COMMENT '客服默认语言',
  `support_langs`            JSON             DEFAULT NULL                   COMMENT '支持的语言列表',
  `auto_reply_enabled`       TINYINT(1)       NOT NULL DEFAULT 1             COMMENT '是否启用 AI 自动回复',
  `business_hours`           JSON             DEFAULT NULL                   COMMENT '工作时段配置',
  `escalation_threshold`     DECIMAL(3,2)     NOT NULL DEFAULT 0.75          COMMENT '人工升级置信度阈值',
  `escalation_amount_limit`  DECIMAL(15,4)    DEFAULT 100.0000               COMMENT '金额超此值自动升级',
  `welcome_message`          VARCHAR(1024)    DEFAULT NULL                   COMMENT '欢迎语模板',
  `signature`                VARCHAR(512)     DEFAULT NULL                   COMMENT 'AI 回复签名',

  `subscription_plan`        VARCHAR(32)      NOT NULL DEFAULT 'FREE'        COMMENT '订阅计划',
  `subscription_started_at`  DATETIME         DEFAULT NULL                   COMMENT '订阅生效时间',
  `subscription_expires_at`  DATETIME         DEFAULT NULL                   COMMENT '订阅到期时间',
  `monthly_token_budget`     BIGINT           NOT NULL DEFAULT 100000        COMMENT '月度Token预算上限',
  `monthly_message_quota`    INT              NOT NULL DEFAULT 1000          COMMENT '月度消息条数上限',
  `qps_limit`                INT              NOT NULL DEFAULT 5             COMMENT 'API 请求 QPS 上限',
  `concurrent_session_limit` INT              NOT NULL DEFAULT 50            COMMENT '同时在线会话数上限',

  `status`                   TINYINT          NOT NULL DEFAULT 1             COMMENT '0:停用 1:启用 2:试用中 3:欠费暂停 4:封禁',
  `status_reason`            VARCHAR(256)     DEFAULT NULL                   COMMENT '当前状态原因',
  `last_active_at`           DATETIME         DEFAULT NULL                   COMMENT '最后活跃时间',
  `ext_attr`                 JSON             DEFAULT NULL                   COMMENT '扩展属性',
  `remark`                   VARCHAR(512)     DEFAULT NULL                   COMMENT '内部备注',

  `is_deleted`               TINYINT(1)       NOT NULL DEFAULT 0             COMMENT '逻辑删除:0否1是',
  `version`                  INT              NOT NULL DEFAULT 0             COMMENT '乐观锁版本号',
  `created_by`               BIGINT           DEFAULT NULL                   COMMENT '创建人ID',
  `updated_by`               BIGINT           DEFAULT NULL                   COMMENT '更新人ID',
  `created_at`               DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3)         COMMENT '创建时间',
  `updated_at`               DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_code`        (`tenant_code`),
  UNIQUE KEY `uk_platform_store`     (`platform`, `external_store_id`, `is_deleted`),
  KEY        `idx_owner_email`       (`owner_email`),
  KEY        `idx_status`            (`status`, `is_deleted`),
  KEY        `idx_subscription`      (`subscription_plan`, `subscription_expires_at`),
  KEY        `idx_last_active`       (`last_active_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户/店铺表';

-- 保留现有业务数据使用的真实租户 1001 / 1002（不覆盖、不清空）。
INSERT IGNORE INTO `tenant`
  (`id`, `tenant_code`, `store_name`, `platform`, `external_store_id`, `owner_email`, `owner_name`, `status`, `default_lang`)
VALUES
  (1001, 'OM-1001', '演示店铺 1001', 'custom', 'local-1001', 'owner1001@example.com', '租户 1001 负责人', 1, 'zh'),
  (1002, 'OM-1002', '演示店铺 1002', 'custom', 'local-1002', 'owner1002@example.com', '租户 1002 负责人', 1, 'zh');
