package com.atm.javaclaw.agent.tools;

import com.atm.javaclaw.agent.skills.SkillContentProvider;
import com.atm.javaclaw.agent.skills.SkillContentProvider.SkillGroupSummary;
import com.atm.javaclaw.agent.skills.SkillContentProvider.SkillSummary;
import com.atm.javaclaw.agent.skills.SkillGroupContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ListSkillsByGroupTool {

    private static final Logger log = LoggerFactory.getLogger(ListSkillsByGroupTool.class);

    private final SkillContentProvider skillContentProvider;

    public ListSkillsByGroupTool(SkillContentProvider skillContentProvider) {
        this.skillContentProvider = skillContentProvider;
    }

    @Tool(description = "根据技能分组名查询可用技能列表。支持同时传入多个分组名（逗号分隔），返回所有匹配分组下的技能名称和描述。如不传分组名则返回所有分组概览。")
    public String listSkillsByGroup(
            @ToolParam(description = "技能分组名称，多个分组用逗号分隔（如 'coding,testing'）。留空返回所有分组概览。")
            String groups
    ) {
        log.info("listSkillsByGroup called with groups: {}", groups);

        if (groups == null || groups.isBlank()) {
            return buildGroupOverview();
        }

        List<String> groupNames = Arrays.stream(groups.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (groupNames.isEmpty()) {
            return buildGroupOverview();
        }

        List<String> filteredNames = filterByAllowed(groupNames);
        if (filteredNames.isEmpty()) {
            return "当前 Agent 无权访问请求的分组。可用分组:\n" + buildGroupOverview();
        }

        Map<String, List<SkillSummary>> result = skillContentProvider.listSkillsByGroups(filteredNames);
        if (result.isEmpty()) {
            return "未找到匹配的分组或分组下没有技能。可用分组:\n" + buildGroupOverview();
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<SkillSummary>> entry : result.entrySet()) {
            sb.append("## ").append(entry.getKey())
                    .append(" (").append(entry.getValue().size()).append(" 个技能)\n");
            for (SkillSummary skill : entry.getValue()) {
                sb.append("- **").append(skill.name()).append("**: ")
                        .append(skill.description() != null ? skill.description() : "")
                        .append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String buildGroupOverview() {
        List<SkillGroupSummary> groups = skillContentProvider.listGroups();
        if (groups.isEmpty()) {
            return "当前没有配置任何技能分组。";
        }

        Set<String> allowed = SkillGroupContext.get();
        if (allowed != null) {
            groups = groups.stream()
                    .filter(g -> allowed.contains(g.name()))
                    .toList();
            if (groups.isEmpty()) {
                return "当前 Agent 没有被授权任何技能分组。";
            }
        }

        StringBuilder sb = new StringBuilder("可用技能分组：\n\n");
        for (SkillGroupSummary g : groups) {
            sb.append("- **").append(g.name()).append("**");
            if (g.displayName() != null && !g.displayName().equals(g.name())) {
                sb.append(" (").append(g.displayName()).append(")");
            }
            sb.append(": ").append(g.description() != null ? g.description() : "")
                    .append(" [").append(g.skillCount()).append(" 个技能]\n");
        }
        sb.append("\n使用 `listSkillsByGroup` 并传入分组名获取具体技能列表。");
        return sb.toString();
    }

    private List<String> filterByAllowed(List<String> requested) {
        Set<String> allowed = SkillGroupContext.get();
        if (allowed == null) {
            return requested;
        }
        return requested.stream()
                .filter(allowed::contains)
                .toList();
    }
}
