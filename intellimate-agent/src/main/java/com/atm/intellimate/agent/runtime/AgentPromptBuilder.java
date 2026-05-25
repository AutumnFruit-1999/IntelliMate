package com.atm.intellimate.agent.runtime;

import com.atm.intellimate.agent.skills.SkillContentProvider;
import com.atm.intellimate.agent.skills.SkillGroupContext;
import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.core.prompt.PromptLoader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class AgentPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(AgentPromptBuilder.class);

    private static final int TOTAL_MAX_CHARS = 150_000;
    private static final Set<String> SKILL_GROUPS_UNRESTRICTED = Set.of("__ALL__");

    private final SkillContentProvider skillContentProvider;
    private final ConcurrentMap<Long, Set<String>> sessionSkillGroups = new ConcurrentHashMap<>();

    public AgentPromptBuilder(@Autowired(required = false) SkillContentProvider skillContentProvider) {
        this.skillContentProvider = skillContentProvider;
    }

    public String buildSystemPrompt(IntelliMateProperties.Agent agentConfig,
                                    List<SkillContentProvider.SkillSummary> skillSummaries,
                                    boolean parallelEnabled,
                                    String planContext,
                                    boolean forcePlan,
                                    String skillGroupsEnabled) {
        StringBuilder sb = new StringBuilder();

        appendSection(sb, "soul", agentConfig.getSoulMd());
        appendSection(sb, "agents", agentConfig.getAgentsMd());

        String skillsSection = buildSkillsDiscovery(skillSummaries, skillGroupsEnabled);
        if (skillsSection != null && !skillsSection.isBlank()) {
            appendSection(sb, "skills", skillsSection);
        }

        appendSection(sb, "plan_system", buildPlanSystemSection(forcePlan));

        if (planContext != null && !planContext.isBlank()) {
            appendSection(sb, "plan_execution", planContext);
        }

        String parallelSection = parallelEnabled
                ? "\n\n### 并行与串行调用\n\n"
                + "当多个工具调用彼此独立、无数据依赖时，**必须**在同一轮回复中同时调用。\n"
                + "适用场景：读取多个文件、搜索多个关键词、执行多个不相关的命令。\n\n"
                + "仅当某个工具的输出必须作为另一个工具的输入时，才采用顺序调用。\n"
                + "禁止并行的场景：文件写入后需要验证结果、有明确的先后依赖关系。\n"
                : "";
        appendSection(sb, "tool_guidelines", PromptLoader.format("prompts/tool-usage-guidelines.md", parallelSection));

        String prompt = sb.toString();
        if (prompt.length() > TOTAL_MAX_CHARS) {
            prompt = prompt.substring(0, TOTAL_MAX_CHARS) + "\n...[truncated]";
        }
        return prompt;
    }

    public void setupSkillGroupContext(Long sessionId, String skillGroupsEnabled) {
        Set<String> resolved;
        if (skillGroupsEnabled == null || skillGroupsEnabled.isBlank()) {
            resolved = Set.of();
        } else if ("full".equalsIgnoreCase(skillGroupsEnabled.trim())) {
            resolved = null;
        } else {
            Set<String> allowed = parseJsonStringArray(skillGroupsEnabled);
            resolved = allowed.isEmpty() ? Set.of() : allowed;
        }
        SkillGroupContext.set(resolved);
        if (sessionId != null) {
            sessionSkillGroups.put(sessionId, resolved != null ? resolved : SKILL_GROUPS_UNRESTRICTED);
        }
    }

    public Set<String> getSessionSkillGroups(Long sessionId) {
        return sessionSkillGroups.get(sessionId);
    }

    public void removeSessionSkillGroups(Long sessionId) {
        sessionSkillGroups.remove(sessionId);
    }

    public boolean isUnrestrictedSkillGroups(Set<String> skillGroups) {
        return skillGroups == SKILL_GROUPS_UNRESTRICTED;
    }

    private String buildPlanSystemSection(boolean forcePlan) {
        StringBuilder sb = new StringBuilder();
        sb.append(PromptLoader.load("prompts/plan-system.md"));
        if (forcePlan) {
            sb.append("\n\n**重要指令：你必须先调用 `writePlan` 创建计划，等待用户审批后再执行。在审批通过之前，不要调用任何其他工具或直接开始执行任务。**");
        }
        return sb.toString();
    }

    private String buildSkillsDiscovery(List<SkillContentProvider.SkillSummary> skills, String skillGroupsEnabled) {
        if (skillContentProvider == null) return null;

        if (skills != null && !skills.isEmpty()) {
            String basePath = skillContentProvider.getSkillsBasePath();
            StringBuilder sb = new StringBuilder();
            sb.append(PromptLoader.load("prompts/skills-discovery.md"));
            for (var skill : skills) {
                sb.append("- **").append(skill.name()).append("**: ")
                        .append(skill.description()).append('\n');
                sb.append("  Read: ").append(basePath).append('/').append(skill.name())
                        .append("/SKILL.md\n");
            }
            return sb.toString();
        }

        if (skillGroupsEnabled != null && !skillGroupsEnabled.isBlank()) {
            List<SkillContentProvider.SkillGroupSummary> allGroups = skillContentProvider.listGroups();

            if (allGroups.isEmpty()) {
                log.warn("skillGroupsEnabled='{}' is set but no enabled skill groups found in DB — " +
                        "check that skill_group table has data and enabled=1", skillGroupsEnabled);
                return null;
            }

            List<SkillContentProvider.SkillGroupSummary> groups = allGroups;
            if (!"full".equalsIgnoreCase(skillGroupsEnabled.trim())) {
                Set<String> allowedNames = parseJsonStringArray(skillGroupsEnabled);
                if (!allowedNames.isEmpty()) {
                    groups = allGroups.stream()
                            .filter(g -> allowedNames.contains(g.name()))
                            .toList();
                    if (groups.isEmpty()) {
                        log.warn("skillGroupsEnabled='{}' matched no groups. Available groups: {}",
                                skillGroupsEnabled,
                                allGroups.stream().map(SkillContentProvider.SkillGroupSummary::name).toList());
                        return null;
                    }
                } else {
                    log.warn("skillGroupsEnabled='{}' parsed to empty set, no groups will be shown in prompt", skillGroupsEnabled);
                    return null;
                }
            }

            if (!groups.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append(PromptLoader.load("prompts/skills-discovery.md"));
                for (var g : groups) {
                    sb.append("- **").append(g.name()).append("**");
                    if (g.displayName() != null && !g.displayName().equals(g.name())) {
                        sb.append(" (").append(g.displayName()).append(")");
                    }
                    sb.append(": ").append(g.description() != null ? g.description() : "");
                    sb.append(" [").append(g.skillCount()).append(" 个技能]\n");
                }
                return sb.toString();
            }
        }

        return null;
    }

    private static Set<String> parseJsonStringArray(String spec) {
        try {
            String trimmed = spec.trim();
            if (trimmed.startsWith("[")) {
                var mapper = new ObjectMapper();
                List<String> list = mapper.readValue(trimmed, new TypeReference<List<String>>() {});
                return new HashSet<>(list);
            }
        } catch (Exception e) {
            log.warn("Failed to parse JSON string array: {}", spec);
        }
        return Set.of();
    }

    private static void appendSection(StringBuilder sb, String tag, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append('<').append(tag).append(">\n");
        sb.append(content.strip());
        sb.append("\n</").append(tag).append('>');
    }
}
