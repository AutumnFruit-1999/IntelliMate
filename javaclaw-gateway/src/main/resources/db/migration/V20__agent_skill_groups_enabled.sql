ALTER TABLE `agent` ADD COLUMN `skill_groups_enabled` VARCHAR(512) NULL COMMENT '技能分组权限: null=无, full=全部, JSON数组=指定分组';
