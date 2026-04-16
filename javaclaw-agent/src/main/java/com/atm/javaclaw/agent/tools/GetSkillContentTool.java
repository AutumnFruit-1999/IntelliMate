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

    @Tool(description = "根据技能名称读取完整的 SKILL.md 内容。当从可用技能列表中识别到匹配技能后，使用此工具获取其详细说明。")
    public String getSkillContent(
            @ToolParam(description = "要查找的技能名称（如 'code-reviewer'）") String skillName
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
