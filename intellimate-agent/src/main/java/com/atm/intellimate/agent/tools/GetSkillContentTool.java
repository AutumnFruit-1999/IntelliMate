package com.atm.intellimate.agent.tools;

import com.atm.intellimate.agent.skills.SkillContentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GetSkillContentTool {

    private static final Logger log = LoggerFactory.getLogger(GetSkillContentTool.class);

    private final SkillContentProvider skillContentProvider;

    public GetSkillContentTool(SkillContentProvider skillContentProvider) {
        this.skillContentProvider = skillContentProvider;
    }

    @Tool(description = "根据技能名称读取完整的 SKILL.md 内容。当从可用技能列表中识别到匹配技能后，使用此工具获取其详细说明。")
    public String getSkillContent(
            @ToolParam(description = "要查找的技能名称（如 'code-reviewer'）") String skillName
    ) {
        log.info("Getting skill content for: {}", skillName);

        if (skillName == null || skillName.isBlank()) {
            return "Error: skillName is required";
        }

        String trimmed = skillName.trim();

        String content = skillContentProvider.readSkillContent(trimmed);
        if (content != null) {
            return content;
        }

        List<String> allNames = skillContentProvider.listAllSkillNames();
        if (allNames == null || allNames.isEmpty()) {
            return "Skill not found: " + trimmed;
        }

        String lowerInput = trimmed.toLowerCase();
        for (String name : allNames) {
            if (name.equalsIgnoreCase(lowerInput)) {
                String matched = skillContentProvider.readSkillContent(name);
                if (matched != null) {
                    log.info("Fuzzy matched '{}' -> '{}'", trimmed, name);
                    return matched;
                }
            }
        }

        for (String name : allNames) {
            if (name.toLowerCase().contains(lowerInput) || lowerInput.contains(name.toLowerCase())) {
                String matched = skillContentProvider.readSkillContent(name);
                if (matched != null) {
                    log.info("Partial matched '{}' -> '{}'", trimmed, name);
                    return matched;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Skill not found: ").append(trimmed).append("\n可用的技能有: ");
        sb.append(String.join(", ", allNames));
        return sb.toString();
    }
}
