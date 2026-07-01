-- V38__memory_system_v3.sql
-- Memory System v3: 分层记忆 + 双通道检索

-- 新增字段
ALTER TABLE agent_memory
    ADD COLUMN keywords TEXT DEFAULT NULL COMMENT '空格分隔的关键词列表，用于 FULLTEXT 检索',
    ADD COLUMN topic VARCHAR(100) DEFAULT NULL COMMENT '主题标签，用于主题聚类和每日整合',
    ADD COLUMN memory_level VARCHAR(20) NOT NULL DEFAULT 'detail' COMMENT 'detail=对话级详细记忆, consolidated=每日整合记忆',
    ADD COLUMN source_memory_ids JSON DEFAULT NULL COMMENT '整合记忆引用的详细记忆ID列表',
    ADD COLUMN enriched_content TEXT DEFAULT NULL COMMENT '语义增强内容（存入Qdrant的文本副本，便于调试）';

-- 为关键词检索创建全文索引（ngram 分词器适配中文）
ALTER TABLE agent_memory ADD FULLTEXT INDEX idx_keywords (keywords) WITH PARSER ngram;

-- 为主题聚类创建索引
ALTER TABLE agent_memory ADD INDEX idx_topic (topic);

-- 为记忆层级创建索引
ALTER TABLE agent_memory ADD INDEX idx_memory_level (memory_level);

-- 新增记忆配置项
INSERT INTO memory_config (agent_name, config_key, config_value) VALUES
    ('_global_', 'vector.similarity_threshold', '0.35'),
    ('_global_', 'consolidation.topic_similarity_threshold', '0.7')
ON DUPLICATE KEY UPDATE config_key = config_key;

-- 新增每日整合定时任务（集成现有 ScheduledJob 框架）
INSERT INTO scheduled_job_config (job_name, job_group, display_name, description, job_class, trigger_type, trigger_value, timezone, enabled, timeout_ms)
VALUES ('memory-daily-consolidation', 'data', '每日记忆整合', '每日记忆整合', 'dailyMemoryConsolidationJob', 'CRON', '0 0 23 * * ?', 'Asia/Shanghai', 1, 1800000)
ON DUPLICATE KEY UPDATE job_name = job_name;
