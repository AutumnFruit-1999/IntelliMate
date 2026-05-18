ALTER TABLE `agent`
    ADD COLUMN `can_delegate` TINYINT NOT NULL DEFAULT 0
        COMMENT '是否允许委派任务给其他 Agent'
        AFTER `skill_groups_enabled`,
    ADD COLUMN `delegate_agents` TEXT NULL
        COMMENT 'JSON 数组: 可委派的目标 Agent 名称列表'
        AFTER `can_delegate`,
    ADD COLUMN `goal` TEXT NULL
        COMMENT 'Agent 目标描述 (用于被委派时传递给调用方)'
        AFTER `delegate_agents`;
