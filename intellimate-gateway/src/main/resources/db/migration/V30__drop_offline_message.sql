-- Remove the offline_message table. Proactive message delivery is now handled
-- entirely through transcript_message persistence + agent.proactive WebSocket replay.
DROP TABLE IF EXISTS offline_message;
