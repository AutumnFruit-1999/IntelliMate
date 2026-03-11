ALTER TABLE `agent` ADD COLUMN `mcp_tools_enabled` TEXT NULL COMMENT 'MCP 工具选择：null=不启用, "full"=全部, JSON数组=指定工具';
