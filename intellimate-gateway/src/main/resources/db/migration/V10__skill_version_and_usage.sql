-- Skill version history
CREATE TABLE IF NOT EXISTS `skill_version` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `skill_id`     BIGINT       NOT NULL COMMENT '关联 skill_definition.id',
    `version`      INT          NOT NULL COMMENT '版本号, 从 1 递增',
    `content`      MEDIUMTEXT   NULL COMMENT '该版本的 SKILL.md 正文',
    `description`  TEXT         NULL COMMENT '该版本的触发描述',
    `change_note`  VARCHAR(512) NULL COMMENT '变更说明',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skill_version` (`skill_id`, `version`),
    FOREIGN KEY (`skill_id`) REFERENCES `skill_definition` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Skill 版本历史';

-- Skill usage statistics
CREATE TABLE IF NOT EXISTS `skill_usage_log` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `skill_name`      VARCHAR(128) NOT NULL,
    `agent_name`      VARCHAR(128) NOT NULL,
    `session_id`      BIGINT       NULL,
    `activated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `activation_type` VARCHAR(32)  NOT NULL COMMENT 'file_read | tool_call',
    PRIMARY KEY (`id`),
    INDEX `idx_skill_usage_name` (`skill_name`),
    INDEX `idx_skill_usage_time` (`activated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Skill 使用统计';
