-- Fix archived sessions that conflict with active session unique key
-- Append '::archived::{id}' to context_id of archived sessions to free up the unique key
UPDATE `session`
SET context_id = CONCAT(context_id, '::archived::', id)
WHERE status = 'archived' AND context_id NOT LIKE '%::archived::%';
