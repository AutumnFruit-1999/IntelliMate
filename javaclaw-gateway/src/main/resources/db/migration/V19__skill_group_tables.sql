CREATE TABLE IF NOT EXISTS `skill_group` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `name`         VARCHAR(64)  NOT NULL COMMENT '分组标识（英文，如 coding/testing/devops）',
    `display_name` VARCHAR(128) NULL COMMENT '显示名称',
    `description`  TEXT         NULL COMMENT '分组描述（告诉模型这个分组包含什么类型的技能）',
    `sort_order`   INT          NOT NULL DEFAULT 0 COMMENT '排序权重，越小越靠前',
    `enabled`      TINYINT      NOT NULL DEFAULT 1,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='技能分组';

CREATE TABLE IF NOT EXISTS `skill_group_member` (
    `id`         BIGINT NOT NULL AUTO_INCREMENT,
    `group_id`   BIGINT NOT NULL,
    `skill_id`   BIGINT NOT NULL,
    `sort_order` INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_skill` (`group_id`, `skill_id`),
    KEY `idx_skill_id` (`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='技能分组关联';
