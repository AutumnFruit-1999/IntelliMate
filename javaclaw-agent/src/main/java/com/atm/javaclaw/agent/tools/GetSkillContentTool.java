package com.atm.javaclaw.agent.tools;

import com.atm.javaclaw.agent.skills.SkillContentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class GetSkillContentTool {

    private static final Logger log = LoggerFactory.getLogger(GetSkillContentTool.class);

    private final SkillContentProvider skillContentProvider;

    public GetSkillContentTool(SkillContentProvider skillContentProvider) {
        this.skillContentProvider = skillContentProvider;
    }

    @Tool(description = "Read the full SKILL.md content for a specific skill by name. "
            + "Use this when you identify a matching skill from the available skills list "
            + "and need its detailed instructions.")
    public String getSkillContent(
            @ToolParam(description = "The skill name to look up (e.g. 'code-reviewer')") String skillName
    ) {
        log.info("Getting skill content for: {}", skillName);

        if (skillName == null || skillName.isBlank()) {
            return "Error: skillName is required";
        }

        String content = skillContentProvider.readSkillContent(skillName.trim());
        if (content == null) {
            return "Skill not found: " + skillName;
        }
        return content;
    }
}
