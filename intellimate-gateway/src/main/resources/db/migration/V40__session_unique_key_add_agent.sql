-- Per-agent session isolation: add agent_name to unique constraint
-- so each agent gets its own session row for the same user

-- 1) Fill NULL agent_name rows with 'default' to satisfy NOT NULL requirement in unique key
UPDATE `session` SET agent_name = 'default' WHERE agent_name IS NULL;

-- 2) Drop old unique constraint (channel_id, context_type, context_id)
ALTER TABLE `session` DROP INDEX `uk_session_key`;

-- 3) Create new unique constraint including agent_name
ALTER TABLE `session` ADD UNIQUE KEY `uk_session_key` (`channel_id`, `context_type`, `context_id`, `agent_name`);
