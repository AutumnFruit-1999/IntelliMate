-- Memory system tables: configuration + long-term memory storage

CREATE TABLE IF NOT EXISTS `memory_config` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `config_key`   VARCHAR(64)  NOT NULL,
    `config_value` VARCHAR(512) NOT NULL,
    `description`  VARCHAR(256) NULL,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Memory system configuration (key-value, UI-editable)';

INSERT INTO `memory_config` (`config_key`, `config_value`, `description`) VALUES
('working.token_budget',             '128000',     '工作记忆 token 容量上限'),
('working.consolidation_threshold',  '0.75',       '触发巩固的使用率阈值 (0.5-0.95)'),
('consolidation.model',              'qwen-turbo', '巩固专用模型'),
('consolidation.fallback_model',     'qwen-lite',  '降级备用模型'),
('consolidation.max_summary_tokens', '1024',       '摘要最大 token 数'),
('consolidation.timeout_ms',         '5000',       '单次巩固调用超时(ms)'),
('consolidation.max_retries',        '2',          '最大重试次数'),
('consolidation.overflow_tolerance', '1.10',       '容量弹性上限 (1.0-1.5)'),
('long_term.enabled',                'false',      '长期记忆开关'),
('long_term.max_memories_per_user',  '500',        '单用户最大记忆条数'),
('long_term.max_injection_tokens',   '2048',       '检索注入 token 预算'),
('long_term.decay_lambda',           '0.1',        '遗忘曲线衰减速率 (约7天衰减到50%)'),
('long_term.compaction_threshold',   '300',        '触发记忆压实的条数'),
('long_term.archive_after_days',     '30',         '冷记忆归档天数');

CREATE TABLE IF NOT EXISTS `agent_memory` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`           VARCHAR(64)  NOT NULL,
    `memory_type`       VARCHAR(16)  NOT NULL COMMENT 'episodic / semantic / procedural',
    `content`           TEXT         NOT NULL,
    `importance`        FLOAT        NOT NULL DEFAULT 0.5,
    `access_count`      INT          NOT NULL DEFAULT 0,
    `last_accessed_at`  DATETIME     NULL,
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `source_session_id` BIGINT       NULL,
    `metadata_json`     TEXT         NULL,
    PRIMARY KEY (`id`),
    INDEX `idx_memory_user_type` (`user_id`, `memory_type`),
    INDEX `idx_memory_importance` (`user_id`, `importance` DESC),
    INDEX `idx_memory_accessed` (`user_id`, `last_accessed_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Long-term memory storage (episodic / semantic / procedural)';
