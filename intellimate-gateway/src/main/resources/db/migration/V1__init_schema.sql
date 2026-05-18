-- IntelliMate Initial Schema
-- 7 tables: agent, session, transcript_message, channel_config, allowlist_entry, pairing_request, audit_log

CREATE TABLE IF NOT EXISTS `agent` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `name`             VARCHAR(128) NOT NULL COMMENT 'Agent unique name',
    `model`            VARCHAR(64)  NOT NULL DEFAULT 'qwen-max' COMMENT 'LLM model identifier',
    `system_prompt`    TEXT         NULL COMMENT 'Custom system prompt',
    `max_turns`        INT          NOT NULL DEFAULT 128,
    `timeout_seconds`  INT          NOT NULL DEFAULT 300,
    `tools_enabled`    JSON         NULL COMMENT 'List of enabled tool names',
    `config_json`      JSON         NULL COMMENT 'Extra agent configuration',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`          TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent definitions';

CREATE TABLE IF NOT EXISTS `session` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `channel_id`       VARCHAR(64)  NOT NULL COMMENT 'Channel adapter identifier',
    `context_type`     VARCHAR(16)  NOT NULL COMMENT 'dm / group / channel',
    `context_id`       VARCHAR(256) NOT NULL COMMENT 'Unique context identifier within channel',
    `agent_name`       VARCHAR(128) NULL COMMENT 'Associated agent name',
    `last_active_at`   DATETIME     NULL,
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`          TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_key` (`channel_id`, `context_type`, `context_id`),
    INDEX `idx_session_agent` (`agent_name`),
    INDEX `idx_session_active` (`last_active_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Conversation sessions';

CREATE TABLE IF NOT EXISTS `transcript_message` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`       BIGINT       NOT NULL,
    `role`             VARCHAR(16)  NOT NULL COMMENT 'user / assistant / system / tool',
    `content`          MEDIUMTEXT   NULL COMMENT 'Message text content',
    `tool_call_id`     VARCHAR(128) NULL COMMENT 'Tool call identifier if role=tool',
    `tool_name`        VARCHAR(64)  NULL COMMENT 'Tool name if role=tool',
    `metadata_json`    JSON         NULL COMMENT 'Extra metadata (attachments, token usage, etc.)',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_transcript_session` (`session_id`, `created_at`),
    CONSTRAINT `fk_transcript_session` FOREIGN KEY (`session_id`) REFERENCES `session`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Conversation transcript messages';

CREATE TABLE IF NOT EXISTS `channel_config` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `channel_id`       VARCHAR(64)  NOT NULL COMMENT 'Channel adapter identifier',
    `enabled`          TINYINT      NOT NULL DEFAULT 1,
    `config_json`      JSON         NOT NULL COMMENT 'Channel-specific configuration',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`          TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_channel_config` (`channel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Channel adapter configurations';

CREATE TABLE IF NOT EXISTS `allowlist_entry` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `channel_id`       VARCHAR(64)  NOT NULL COMMENT 'Channel adapter identifier',
    `sender_id`        VARCHAR(256) NOT NULL COMMENT 'Allowed sender identifier',
    `note`             VARCHAR(256) NULL COMMENT 'Human-readable note',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted`          TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_allowlist` (`channel_id`, `sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Per-channel sender allowlist';

CREATE TABLE IF NOT EXISTS `pairing_request` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `channel_id`       VARCHAR(64)  NOT NULL,
    `sender_id`        VARCHAR(256) NOT NULL,
    `pairing_code`     VARCHAR(32)  NOT NULL COMMENT '6-digit pairing code',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending / approved / rejected / expired',
    `expires_at`       DATETIME     NOT NULL,
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_pairing_code` (`pairing_code`),
    INDEX `idx_pairing_sender` (`channel_id`, `sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='DM pairing requests';

CREATE TABLE IF NOT EXISTS `audit_log` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `action`           VARCHAR(64)  NOT NULL COMMENT 'Action type (e.g. message.send, tool.exec, session.reset)',
    `actor`            VARCHAR(256) NULL COMMENT 'Who triggered the action',
    `session_id`       BIGINT       NULL,
    `detail`           TEXT         NULL COMMENT 'Detailed description or JSON payload',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_audit_action` (`action`, `created_at`),
    INDEX `idx_audit_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Audit trail';
