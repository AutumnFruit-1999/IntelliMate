-- Plan Mode: plan + plan_step tables, transcript_message.plan_id column

CREATE TABLE IF NOT EXISTS `plan` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`  BIGINT       NOT NULL,
    `title`       VARCHAR(500) NOT NULL,
    `status`      VARCHAR(20)  NOT NULL DEFAULT 'draft',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_plan_session` (`session_id`),
    CONSTRAINT `fk_plan_session` FOREIGN KEY (`session_id`) REFERENCES `session`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Plan definitions';

CREATE TABLE IF NOT EXISTS `plan_step` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `plan_id`         BIGINT       NOT NULL,
    `step_index`      INT          NOT NULL,
    `title`           VARCHAR(500) NOT NULL,
    `description`     TEXT         NULL,
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'pending',
    `result_summary`  TEXT         NULL,
    `started_at`      DATETIME     NULL,
    `completed_at`    DATETIME     NULL,
    PRIMARY KEY (`id`),
    INDEX `idx_plan_step_plan` (`plan_id`),
    CONSTRAINT `fk_plan_step_plan` FOREIGN KEY (`plan_id`) REFERENCES `plan`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Plan step details';

ALTER TABLE `transcript_message` ADD COLUMN `plan_id` BIGINT NULL;
CREATE INDEX `idx_transcript_plan` ON `transcript_message`(`plan_id`);
