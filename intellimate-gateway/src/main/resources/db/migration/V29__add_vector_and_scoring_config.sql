-- V29: Add agent_name column (if absent) + vector/scoring config

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'memory_config' AND COLUMN_NAME = 'agent_name');
SET @sql = IF(@col_exists = 0,
  'ALTER TABLE `memory_config` ADD COLUMN `agent_name` VARCHAR(128) NOT NULL DEFAULT ''_global_'' AFTER `id`',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_old = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'memory_config' AND INDEX_NAME = 'uk_config_key');
SET @sql2 = IF(@idx_old > 0, 'ALTER TABLE `memory_config` DROP INDEX `uk_config_key`', 'SELECT 1');
PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

SET @idx_new = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'memory_config' AND INDEX_NAME = 'uk_agent_config_key');
SET @sql3 = IF(@idx_new = 0,
  'ALTER TABLE `memory_config` ADD UNIQUE KEY `uk_agent_config_key` (`agent_name`, `config_key`)',
  'SELECT 1');
PREPARE stmt3 FROM @sql3;
EXECUTE stmt3;
DEALLOCATE PREPARE stmt3;

UPDATE `memory_config` SET `agent_name` = '_global_' WHERE `agent_name` = '' OR `agent_name` IS NULL;

INSERT INTO memory_config (agent_name, config_key, config_value, description) VALUES
('_global_', 'vector.enabled', 'true', '向量检索主开关'),
('_global_', 'embedding.model', 'text-embedding-v3', 'Embedding 模型名称'),
('_global_', 'embedding.dimensions', '1024', '向量维度'),
('_global_', 'retrieval.strategy', 'hybrid', '检索策略: hybrid/vector-only/keyword-only'),
('_global_', 'retrieval.vector_weight', '0.6', '向量得分权重'),
('_global_', 'retrieval.keyword_weight', '0.4', '关键词得分权重'),
('_global_', 'scoring.semantic_weight', '1.2', 'semantic 类型权重'),
('_global_', 'scoring.episodic_weight', '0.8', 'episodic 类型权重'),
('_global_', 'scoring.procedural_weight', '1.0', 'procedural 类型权重'),
('_global_', 'scoring.semantic_decay_lambda', '0.03', 'semantic 衰减系数'),
('_global_', 'scoring.episodic_decay_lambda', '0.10', 'episodic 衰减系数'),
('_global_', 'scoring.procedural_decay_lambda', '0.05', 'procedural 衰减系数'),
('_global_', 'long_term.min_fact_importance', '0.3', '低于此 importance 的 fact 不存储'),
('_global_', 'long_term.max_merged_content_length', '1000', '合并后单条记忆最大字符数')
ON DUPLICATE KEY UPDATE config_key = config_key;
