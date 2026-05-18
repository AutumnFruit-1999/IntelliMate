CREATE TABLE IF NOT EXISTS `skill_definition` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `name`             VARCHAR(128) NOT NULL COMMENT 'Skill 唯一标识（AgentSkills 兼容, 小写+连字符）',
    `display_name`     VARCHAR(256) NULL COMMENT '显示名称',
    `description`      TEXT         NOT NULL COMMENT '触发描述（Agent 用来判断何时激活）',
    `content`          MEDIUMTEXT   NULL COMMENT 'SKILL.md 正文（Markdown 指令）',
    `tags`             VARCHAR(512) NULL COMMENT '逗号分隔的标签',
    `metadata`         JSON         NULL COMMENT '兼容 AgentSkills metadata',
    `has_scripts`      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否包含 scripts/ 目录',
    `has_references`   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否包含 references/ 目录',
    `enabled`          TINYINT      NOT NULL DEFAULT 1 COMMENT '全局启用/禁用',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skill_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Skill 定义';

ALTER TABLE `agent`
    ADD COLUMN `skills_enabled` TEXT NULL
    COMMENT 'JSON array of enabled skill names, null=none, "full"=all'
    AFTER `mcp_tools_enabled`;
