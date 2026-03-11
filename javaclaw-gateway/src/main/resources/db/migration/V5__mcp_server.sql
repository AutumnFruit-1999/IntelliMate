CREATE TABLE IF NOT EXISTS `mcp_server` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `name`               VARCHAR(128) NOT NULL COMMENT 'MCP 服务显示名称',
    `server_url`         VARCHAR(512) NOT NULL COMMENT 'SSE/HTTP URL 或 STDIO 命令 JSON',
    `transport_type`     VARCHAR(32)  NOT NULL COMMENT '传输类型：SSE / STDIO / STREAMABLE_HTTP',
    `auth_config`        JSON         NULL COMMENT '认证配置',
    `agent_name`         VARCHAR(128) NULL COMMENT '绑定 Agent（NULL=全局）',
    `enabled`            TINYINT      NOT NULL DEFAULT 1,
    `last_connected_at`  DATETIME     NULL COMMENT '最后成功连接时间',
    `tools_discovered`   JSON         NULL COMMENT '缓存：上次发现的工具列表',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mcp_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='MCP Server 连接配置';
