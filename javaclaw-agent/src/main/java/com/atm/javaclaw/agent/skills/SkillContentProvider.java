package com.atm.javaclaw.agent.skills;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * SPI for resolving skill summaries at Discovery phase.
 * Defined in javaclaw-agent, implemented in javaclaw-gateway.
 */
public interface SkillContentProvider {

    /**
     * Returns skill index information for the Discovery phase (reactive).
     *
     * @param skillsEnabledSpec null=none, "full"=all enabled, JSON array=specific names
     * @return Mono of skill summaries (name + description only)
     */
    Mono<List<SkillSummary>> resolveSkillSummaries(String skillsEnabledSpec);

    /**
     * Returns the base path of the skills directory on the file system.
     */
    String getSkillsBasePath();

    /**
     * Reads the full SKILL.md content for a specific skill by name.
     * Used by GetSkillContentTool as a fallback when the agent cannot use file_read.
     *
     * @param skillName the skill name to look up
     * @return the full SKILL.md content, or null if not found
     */
    String readSkillContent(String skillName);

    record SkillSummary(String name, String description) {}
}
