package com.atm.javaclaw.agent.skills;

/**
 * SPI for recording skill activation events.
 * Defined in javaclaw-agent, implemented in javaclaw-gateway.
 */
public interface SkillUsageRecorder {

    /**
     * Records that a skill was activated during an agent run.
     *
     * @param skillName      the skill name that was activated
     * @param agentName      the agent that activated the skill
     * @param sessionId      the session in which the activation occurred
     * @param activationType how the skill was activated (e.g. "file_read", "tool_call")
     */
    void recordActivation(String skillName, String agentName, Long sessionId, String activationType);
}
