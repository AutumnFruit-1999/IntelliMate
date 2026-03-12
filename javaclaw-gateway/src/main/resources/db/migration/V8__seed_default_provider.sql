INSERT INTO `model_provider` (`name`, `type`, `base_url`, `api_key_encrypted`, `enabled`, `sort_order`)
VALUES ('阿里 DashScope', 'DASHSCOPE', NULL, '__PLACEHOLDER__', 1, 0)
ON DUPLICATE KEY UPDATE `id` = `id`;

INSERT INTO `model_definition` (`provider_id`, `model_id`, `display_name`, `description`, `enabled`, `sort_order`)
SELECT p.id, m.model_id, m.display_name, m.description, 1, m.sort_order
FROM `model_provider` p
CROSS JOIN (
    SELECT 'qwen-plus'  AS model_id, '通义千问 Plus'  AS display_name, '均衡型，适合大多数场景'   AS description, 0 AS sort_order UNION ALL
    SELECT 'qwen-max',              '通义千问 Max',               '旗舰型，推理能力最强',              1 UNION ALL
    SELECT 'qwen-turbo',            '通义千问 Turbo',             '速度快，适合简单任务',              2 UNION ALL
    SELECT 'qwen-long',             '通义千问 Long',              '长上下文，适合长文档处理',           3
) m
WHERE p.name = '阿里 DashScope'
ON DUPLICATE KEY UPDATE `display_name` = VALUES(`display_name`);
