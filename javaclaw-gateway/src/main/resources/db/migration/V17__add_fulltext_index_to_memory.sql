-- Add FULLTEXT index with ngram parser for efficient keyword search on memory content.
-- ngram parser supports CJK languages and short tokens without requiring word boundaries.

ALTER TABLE `agent_memory` ADD FULLTEXT INDEX `idx_memory_fulltext` (`content`) WITH PARSER ngram;
