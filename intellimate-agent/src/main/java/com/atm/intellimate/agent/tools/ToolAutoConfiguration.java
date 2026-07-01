package com.atm.intellimate.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class ToolAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolAutoConfiguration.class);

    @Bean
    public ToolCallbackProvider toolCallbackProvider(
            ExecTool execTool,
            FileReadTool fileReadTool,
            FileWriteTool fileWriteTool,
            FileEditTool fileEditTool,
            WebSearchTool webSearchTool,
            WebFetchTool webFetchTool,
            DelegateAgentTool delegateAgentTool,
            HandoffTool handoffTool,
            DelegateParallelTool delegateParallelTool,
            @Autowired(required = false) GetSkillContentTool getSkillContentTool,
            @Autowired(required = false) ListSkillsByGroupTool listSkillsByGroupTool
    ) {
        List<Object> tools = new ArrayList<>(List.of(
                execTool, fileReadTool, fileWriteTool, fileEditTool, webSearchTool, webFetchTool,
                delegateAgentTool, handoffTool, delegateParallelTool));
        if (getSkillContentTool != null) {
            tools.add(getSkillContentTool);
        } else {
            log.warn("GetSkillContentTool not available — SkillContentProvider bean may be missing");
        }
        if (listSkillsByGroupTool != null) {
            tools.add(listSkillsByGroupTool);
        } else {
            log.warn("ListSkillsByGroupTool not available — SkillContentProvider bean may be missing");
        }
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools.toArray())
                .build();
    }
}
