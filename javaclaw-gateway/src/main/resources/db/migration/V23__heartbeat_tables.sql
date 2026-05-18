-- Agent 心跳系统数据库表
-- 心跳配置表
CREATE TABLE heartbeat_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    enabled TINYINT DEFAULT 0,
    timezone VARCHAR(50) DEFAULT 'Asia/Shanghai',
    wake_time VARCHAR(5) DEFAULT '06:00',
    sleep_time VARCHAR(5) DEFAULT '23:00',
    heartbeat_interval_minutes INT DEFAULT 60,
    personality_prompt TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 心跳执行日志
CREATE TABLE heartbeat_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    state VARCHAR(20) NOT NULL COMMENT 'SLEEPING/WAKING/ACTIVE/WINDING_DOWN',
    triggered_at DATETIME NOT NULL,
    prompt_used TEXT,
    response TEXT,
    delivered TINYINT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_agent_time (agent_id, triggered_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 待办任务表
CREATE TABLE agent_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    user_id VARCHAR(100) DEFAULT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT,
    due_at DATETIME DEFAULT NULL,
    remind_at DATETIME DEFAULT NULL,
    status VARCHAR(20) DEFAULT 'pending' COMMENT 'pending/done/cancelled',
    priority INT DEFAULT 0 COMMENT '0=normal, 1=important, 2=urgent',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_status (agent_id, status),
    INDEX idx_remind (remind_at, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 离线消息队列
CREATE TABLE offline_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    message_type VARCHAR(20) DEFAULT 'heartbeat' COMMENT 'heartbeat/task_remind/greeting',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    delivered TINYINT DEFAULT 0,
    delivered_at DATETIME DEFAULT NULL,
    INDEX idx_agent_pending (agent_id, delivered, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
