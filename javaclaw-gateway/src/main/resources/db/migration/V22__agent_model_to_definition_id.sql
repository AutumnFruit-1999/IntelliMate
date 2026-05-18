-- Migrate agent.model from modelId string to model_definition.id
-- This resolves the bug where same model names from different providers collide
UPDATE agent a
INNER JOIN model_definition md ON md.model_id = a.model COLLATE utf8mb4_unicode_ci AND md.enabled = 1
SET a.model = CAST(md.id AS CHAR)
WHERE a.model IS NOT NULL
  AND a.model != ''
  AND a.model NOT REGEXP '^[0-9]+$';
