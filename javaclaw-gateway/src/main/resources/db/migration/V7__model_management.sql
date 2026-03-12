CREATE TABLE IF NOT EXISTS `model_provider` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `name`              VARCHAR(128) NOT NULL COMMENT '厂商显示名称',
    `type`              VARCHAR(32)  NOT NULL COMMENT '类型：DASHSCOPE / OPENAI_COMPATIBLE / ANTHROPIC',
    `base_url`          VARCHAR(512) NULL     COMMENT 'API 基础地址（留空使用默认）',
    `api_key_encrypted` TEXT         NOT NULL COMMENT '加密后的 API Key',
    `enabled`           TINYINT      NOT NULL DEFAULT 1,
    `sort_order`        INT          NOT NULL DEFAULT 0 COMMENT '排序权重',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `model_definition` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `provider_id`   BIGINT       NOT NULL COMMENT '关联厂商 ID',
    `model_id`      VARCHAR(128) NOT NULL COMMENT '模型标识符（传给 API 的值）',
    `display_name`  VARCHAR(128) NOT NULL COMMENT '前端显示名称',
    `description`   TEXT         NULL     COMMENT '模型描述',
    `max_tokens`    INT          NULL     COMMENT '最大输出 token 数',
    `capabilities`  JSON         NULL     COMMENT '能力标签',
    `enabled`       TINYINT      NOT NULL DEFAULT 1,
    `sort_order`    INT          NOT NULL DEFAULT 0,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_provider_model` (`provider_id`, `model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
