-- V30: model_definition 新增 category + dimensions 列，memory_config 新增 embedding.definition_id

SET @col_cat = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_definition' AND COLUMN_NAME = 'category');
SET @sql_cat = IF(@col_cat = 0,
  'ALTER TABLE `model_definition` ADD COLUMN `category` VARCHAR(16) NOT NULL DEFAULT ''CHAT'' AFTER `model_id`',
  'SELECT 1');
PREPARE stmt_cat FROM @sql_cat;
EXECUTE stmt_cat;
DEALLOCATE PREPARE stmt_cat;

SET @col_dim = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_definition' AND COLUMN_NAME = 'dimensions');
SET @sql_dim = IF(@col_dim = 0,
  'ALTER TABLE `model_definition` ADD COLUMN `dimensions` INT DEFAULT NULL AFTER `category`',
  'SELECT 1');
PREPARE stmt_dim FROM @sql_dim;
EXECUTE stmt_dim;
DEALLOCATE PREPARE stmt_dim;

INSERT INTO memory_config (agent_name, config_key, config_value, description) VALUES
('_global_', 'embedding.definition_id', '', '当前使用的 Embedding 模型 definition ID（空=向量功能禁用）')
ON DUPLICATE KEY UPDATE config_key = config_key;
