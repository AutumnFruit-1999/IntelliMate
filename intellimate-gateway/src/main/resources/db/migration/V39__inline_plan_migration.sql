-- V39: Plan 模式重构 — plan/plan_step 数据迁移到 transcript_message，废弃旧表
-- 设计规格: docs/superpowers/specs/2026-06-11-plan-mode-refactor-design.md

-- 1. 迁移现有 plan + plan_step 数据为 plan 类型的 transcript_message
--    每条 plan 记录转换为一条 role='assistant' 的消息，plan 数据存储在 metadata_json 中
INSERT INTO transcript_message (session_id, role, content, metadata_json, created_at)
SELECT
    p.session_id,
    'assistant',
    p.title,
    JSON_OBJECT(
        'type', 'plan',
        'plan', JSON_OBJECT(
            'status', p.status,
            'completionSummary', p.completion_summary,
            'steps', COALESCE(
                (
                    SELECT JSON_ARRAYAGG(
                        JSON_OBJECT(
                            'index', ps.step_index,
                            'title', ps.title,
                            'description', COALESCE(ps.description, ''),
                            'verification', '',
                            'status', ps.status,
                            'resultSummary', ps.result_summary
                        )
                    )
                    FROM plan_step ps
                    WHERE ps.plan_id = p.id
                    ORDER BY ps.step_index
                ),
                JSON_ARRAY()
            )
        )
    ),
    p.created_at
FROM plan p;

-- 2. 删除 transcript_message.plan_id 索引和列
DROP INDEX idx_transcript_plan ON transcript_message;
ALTER TABLE transcript_message DROP COLUMN plan_id;

-- 3. 废弃旧表（先子表后父表，避免外键约束冲突）
RENAME TABLE plan_step TO _deprecated_plan_step;
RENAME TABLE plan TO _deprecated_plan;
