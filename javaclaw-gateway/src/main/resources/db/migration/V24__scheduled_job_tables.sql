-- 通用定时任务调度中心

-- 任务配置表
CREATE TABLE scheduled_job_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_name VARCHAR(100) NOT NULL,
    job_group VARCHAR(50) NOT NULL DEFAULT 'system',
    display_name VARCHAR(200) NOT NULL,
    description TEXT,
    job_class VARCHAR(255) NOT NULL,
    trigger_type VARCHAR(20) NOT NULL COMMENT 'CRON / FIXED_RATE / FIXED_DELAY',
    trigger_value VARCHAR(100) NOT NULL,
    timezone VARCHAR(50) DEFAULT 'Asia/Shanghai',
    enabled TINYINT DEFAULT 1,
    max_retry_count INT DEFAULT 0,
    retry_backoff_ms BIGINT DEFAULT 5000,
    timeout_ms BIGINT DEFAULT 300000,
    params_json TEXT,
    concurrent_allowed TINYINT DEFAULT 0,
    next_fire_time DATETIME DEFAULT NULL,
    last_fire_time DATETIME DEFAULT NULL,
    last_status VARCHAR(20) DEFAULT NULL,
    consecutive_failures INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_job_name (job_name),
    INDEX idx_enabled_fire (enabled, next_fire_time),
    INDEX idx_group (job_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 执行日志表
CREATE TABLE scheduled_job_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_name VARCHAR(100) NOT NULL,
    job_group VARCHAR(50) NOT NULL,
    fire_time DATETIME NOT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME DEFAULT NULL,
    duration_ms BIGINT DEFAULT NULL,
    status VARCHAR(20) NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED/TIMEOUT/SKIPPED/RETRYING',
    retry_count INT DEFAULT 0,
    result_message VARCHAR(1000) DEFAULT NULL,
    error_message VARCHAR(500) DEFAULT NULL,
    error_stack TEXT,
    metrics_json TEXT,
    trigger_source VARCHAR(20) DEFAULT 'AUTO' COMMENT 'AUTO/MANUAL/RETRY',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_job_time (job_name, fire_time DESC),
    INDEX idx_status_time (status, created_at DESC),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 初始任务配置数据
INSERT INTO scheduled_job_config (job_name, job_group, display_name, description, job_class, trigger_type, trigger_value, timeout_ms, max_retry_count, retry_backoff_ms) VALUES
('heartbeat-tick', 'agent', 'Agent 心跳检测', '遍历已启用的 Agent 配置，根据生命节律状态机决定是否调用 LLM 发送问候/提醒', 'heartbeatJob', 'FIXED_RATE', '60000', 30000, 0, 5000),
('memory-nightly-maintenance', 'data', '夜间记忆维护', '每日凌晨执行记忆遗忘/压缩/归档冷数据', 'memoryMaintenanceJob', 'CRON', '0 0 3 * * ?', 1800000, 2, 60000),
('data-cleanup', 'data', '数据定期清理', '清理过期执行日志、心跳日志、已投递离线消息', 'dataCleanupJob', 'CRON', '0 30 4 * * ?', 600000, 1, 30000),
('health-check', 'monitor', '系统健康检查', '检测模型接口可用性、Agent 状态、数据库连接池健康度、JVM 内存', 'healthCheckJob', 'FIXED_RATE', '300000', 60000, 0, 5000);
