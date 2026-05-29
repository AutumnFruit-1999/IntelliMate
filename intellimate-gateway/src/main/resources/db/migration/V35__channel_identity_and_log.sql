-- V35__channel_identity_and_log.sql

-- 用户身份映射：将外部渠道身份关联到统一的 IntelliMate 用户
CREATE TABLE channel_identity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL,
    channel_id VARCHAR(32) NOT NULL,
    external_id VARCHAR(128) NOT NULL,
    external_name VARCHAR(128),
    bound_at TIMESTAMP DEFAULT NOW(),
    UNIQUE KEY uk_channel_external (channel_id, external_id),
    INDEX idx_user (user_id)
);

-- 渠道消息日志：审计和排查用
CREATE TABLE channel_message_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    channel_id VARCHAR(32) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    user_id VARCHAR(64),
    external_id VARCHAR(128),
    message_text TEXT,
    status VARCHAR(16) DEFAULT 'success',
    error_message VARCHAR(512),
    created_at TIMESTAMP DEFAULT NOW(),
    INDEX idx_channel_time (channel_id, created_at)
);

-- 会话消息增加来源渠道标记
ALTER TABLE transcript_message
    ADD COLUMN source_channel VARCHAR(32) DEFAULT 'webchat';
