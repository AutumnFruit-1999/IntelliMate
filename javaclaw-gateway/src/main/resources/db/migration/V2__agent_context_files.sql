-- Add SOUL / USER / AGENTS context fields to agent table
ALTER TABLE `agent`
    ADD COLUMN `soul_md`   TEXT NULL COMMENT 'SOUL: personality, tone, boundaries' AFTER `system_prompt`,
    ADD COLUMN `user_md`   TEXT NULL COMMENT 'USER: user profile and preferences' AFTER `soul_md`,
    ADD COLUMN `agents_md` TEXT NULL COMMENT 'AGENTS: operating instructions and rules' AFTER `user_md`;
