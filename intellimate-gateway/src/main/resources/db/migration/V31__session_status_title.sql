ALTER TABLE `session` ADD COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'active' AFTER `agent_name`;
ALTER TABLE `session` ADD COLUMN `title` VARCHAR(200) NULL AFTER `status`;
CREATE INDEX idx_session_agent_status ON `session` (`agent_name`, `status`, `deleted`);
