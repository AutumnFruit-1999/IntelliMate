-- Cold memory archive table for tiered storage

CREATE TABLE IF NOT EXISTS `agent_memory_archive` (
    `id`                BIGINT       NOT NULL,
    `user_id`           VARCHAR(64)  NOT NULL,
    `memory_type`       VARCHAR(16)  NOT NULL,
    `content`           TEXT         NOT NULL,
    `importance`        FLOAT        NOT NULL DEFAULT 0.5,
    `access_count`      INT          NOT NULL DEFAULT 0,
    `last_accessed_at`  DATETIME     NULL,
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `source_session_id` BIGINT       NULL,
    `metadata_json`     TEXT         NULL,
    `archived_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_archive_user_type` (`user_id`, `memory_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Archived cold long-term memories';
