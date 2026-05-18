-- Add agent_id column to memory tables for per-agent isolation

ALTER TABLE `agent_memory`
    ADD COLUMN `agent_id` VARCHAR(64) NULL AFTER `user_id`;

-- Backfill existing rows with 'default'
UPDATE `agent_memory` SET `agent_id` = 'default' WHERE `agent_id` IS NULL;

-- Make NOT NULL after backfill
ALTER TABLE `agent_memory`
    MODIFY COLUMN `agent_id` VARCHAR(64) NOT NULL DEFAULT 'default';

-- Update indexes to include agent_id
DROP INDEX `idx_memory_user_type` ON `agent_memory`;
CREATE INDEX `idx_memory_user_agent_type` ON `agent_memory` (`user_id`, `agent_id`, `memory_type`);

DROP INDEX `idx_memory_importance` ON `agent_memory`;
CREATE INDEX `idx_memory_importance` ON `agent_memory` (`user_id`, `agent_id`, `importance` DESC);

DROP INDEX `idx_memory_accessed` ON `agent_memory`;
CREATE INDEX `idx_memory_accessed` ON `agent_memory` (`user_id`, `agent_id`, `last_accessed_at` DESC);

-- Archive table
ALTER TABLE `agent_memory_archive`
    ADD COLUMN `agent_id` VARCHAR(64) NULL AFTER `user_id`;

UPDATE `agent_memory_archive` SET `agent_id` = 'default' WHERE `agent_id` IS NULL;

ALTER TABLE `agent_memory_archive`
    MODIFY COLUMN `agent_id` VARCHAR(64) NOT NULL DEFAULT 'default';

DROP INDEX `idx_archive_user_type` ON `agent_memory_archive`;
CREATE INDEX `idx_archive_user_agent_type` ON `agent_memory_archive` (`user_id`, `agent_id`, `memory_type`);
