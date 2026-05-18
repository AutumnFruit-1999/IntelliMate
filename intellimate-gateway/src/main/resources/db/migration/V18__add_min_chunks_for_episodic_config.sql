-- Add configurable minimum chunks threshold for episodic memory generation
INSERT INTO `memory_config` (`config_key`, `config_value`, `description`) VALUES
('long_term.min_chunks_for_episodic', '4', '生成长期记忆所需的最小 chunk 数量')
ON DUPLICATE KEY UPDATE `config_value` = VALUES(`config_value`);
