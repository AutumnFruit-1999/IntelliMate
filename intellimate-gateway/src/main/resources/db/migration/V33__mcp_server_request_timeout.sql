ALTER TABLE `mcp_server`
    ADD COLUMN `request_timeout_seconds` INT NULL COMMENT 'MCP 请求超时（秒），NULL 则使用全局默认值'
    AFTER `tools_discovered`;
