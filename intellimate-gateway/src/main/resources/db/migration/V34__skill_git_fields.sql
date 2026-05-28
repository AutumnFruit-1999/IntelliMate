ALTER TABLE skill_definition ADD COLUMN git_url VARCHAR(512) NULL COMMENT 'Git 仓库来源 URL';
ALTER TABLE skill_definition ADD COLUMN git_sub_path VARCHAR(256) NULL COMMENT 'monorepo 中的子目录路径';
