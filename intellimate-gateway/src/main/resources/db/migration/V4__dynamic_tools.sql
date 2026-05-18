CREATE TABLE IF NOT EXISTS `tool_definition` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `name`              VARCHAR(128) NOT NULL COMMENT '工具名称（唯一标识）',
    `type`              VARCHAR(32)  NOT NULL COMMENT '类型：HTTP_API / SHELL_COMMAND / BUILTIN_OVERRIDE',
    `description`       TEXT         NULL COMMENT '工具描述（LLM 可见）',
    `parameters_schema` JSON         NULL COMMENT '参数定义（JSON Schema 格式）',
    `execution_config`  JSON         NULL COMMENT '执行配置',
    `timeout_seconds`   INT          NOT NULL DEFAULT 30 COMMENT '执行超时（秒）',
    `group_name`        VARCHAR(64)  NULL COMMENT '所属分组名称',
    `agent_name`        VARCHAR(128) NULL COMMENT '绑定 Agent（NULL=全局）',
    `enabled`           TINYINT      NOT NULL DEFAULT 1,
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tool_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='自定义工具定义';
