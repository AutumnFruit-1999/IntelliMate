package com.atm.intellimate.agent.skills;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * SPI for resolving skill summaries at Discovery phase.
 * Defined in intellimate-agent, implemented in intellimate-gateway.
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

    /**
     * Returns all enabled skill groups with their metadata and skill count.
     */
    List<SkillGroupSummary> listGroups();

    /**
     * Returns skills organized by group names.
     * @param groupNames list of group name identifiers
     * @return map of groupName -> skills in that group
     */
    Map<String, List<SkillSummary>> listSkillsByGroups(List<String> groupNames);

    /**
     * Returns all skill names (for fuzzy-match fallback in getSkillContent).
     */
    List<String> listAllSkillNames();

    record SkillSummary(String name, String description) {}

    record SkillGroupSummary(String name, String displayName, String description, int skillCount) {}
}
